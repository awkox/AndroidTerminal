#include <jni.h>

#include <libssh/libssh.h>

#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <new>
#include <string>

namespace {

struct native_ssh {
    ssh_session session = nullptr;
    ssh_channel channel = nullptr;
    int native_fd = -1;
    volatile bool killed = false;
    int exit_st = -1;
    pthread_t reader = 0;
    pthread_t writer = 0;
    bool reader_joined = true;
    bool writer_joined = true;
};

constexpr size_t kIoBuffer = 4096;

std::string ssh_error_str(ssh_session session) {
    const char* detail = session != nullptr ? ssh_get_error(session) : "null session";
    return detail != nullptr ? detail : "unknown error";
}

void* io_reader(void* arg) {
    native_ssh* h = static_cast<native_ssh*>(arg);
    char buf[kIoBuffer];
    for (;;) {
        ssize_t n = read(h->native_fd, buf, sizeof(buf));
        if (n <= 0) {
            break;
        }
        size_t off = 0;
        while (off < static_cast<size_t>(n)) {
            if (h->killed) {
                return nullptr;
            }
            int written = ssh_channel_write(h->channel, buf + off,
                                            static_cast<uint32_t>(n - off));
            if (written == SSH_AGAIN) {
                usleep(10000);
                continue;
            }
            if (written < 0) {
                return nullptr;
            }
            off += static_cast<size_t>(written);
        }
    }
    if (!h->killed) {
        ssh_channel_send_eof(h->channel);
    }
    return nullptr;
}

void* io_writer(void* arg) {
    native_ssh* h = static_cast<native_ssh*>(arg);
    char buf[kIoBuffer];
    for (;;) {
        if (h->killed) {
            break;
        }
        // is_blocking=1 在本实现中会立即返回 SSH_AGAIN，必须用非阻塞轮询。
        int n = ssh_channel_read(h->channel, buf, sizeof(buf), 0);
        if (n == SSH_AGAIN) {
            usleep(10000);
            continue;
        }
        if (n <= 0) {
            break;
        }
        size_t off = 0;
        while (off < static_cast<size_t>(n)) {
            ssize_t written = write(h->native_fd, buf + off,
                                    static_cast<size_t>(n) - off);
            if (written <= 0) {
                break;
            }
            off += static_cast<size_t>(written);
        }
    }
    shutdown(h->native_fd, SHUT_WR);
    if (!h->killed) {
        // 被 kill 时通道可能已被并发释放，只采集退出状态（正常路径）
        uint32_t exit_state = UINT32_MAX;
        ssh_channel_get_exit_state(h->channel, &exit_state, nullptr, nullptr);
        h->exit_st = exit_state != UINT32_MAX ? static_cast<int>(exit_state) : -1;
    }
    return nullptr;
}

void free_channel(native_ssh* h) {
    if (h->channel != nullptr) {
        ssh_channel_free(h->channel);
        h->channel = nullptr;
    }
}

void teardown(native_ssh* h, bool join) {
    h->killed = true;
    if (join) {
        // 先让工作线程自然退出，再动 libssh 对象
        if (!h->writer_joined && h->writer != 0) {
            pthread_join(h->writer, nullptr);
            h->writer_joined = true;
        }
        if (!h->reader_joined && h->reader != 0) {
            pthread_join(h->reader, nullptr);
            h->reader_joined = true;
        }
    }
    // 0.12 的 ssh_disconnect 会 do_free session->channels 里的全部 channel，
    // 因此 ssh_channel_free 必须排在 ssh_disconnect 之前，否则构成二次释放。
    free_channel(h);
    if (h->session != nullptr) {
        ssh_disconnect(h->session);
        ssh_free(h->session);
        h->session = nullptr;
    }
    if (h->native_fd >= 0) {
        close(h->native_fd);
        h->native_fd = -1;
    }
}

void dispose(native_ssh* h) {
    if (h == nullptr) {
        return;
    }
    teardown(h, true);
    delete h;
}

std::string jstring_copy(JNIEnv* env, jstring str) {
    if (str == nullptr) {
        return {};
    }
    const char* utf = env->GetStringUTFChars(str, nullptr);
    if (utf == nullptr) {
        return {};
    }
    std::string copy(utf);
    env->ReleaseStringUTFChars(str, utf);
    return copy;
}

std::string server_fingerprint_of(ssh_session session) {
    if (session == nullptr) {
        return {};
    }
    ssh_key key = nullptr;
    if (ssh_get_server_publickey(session, &key) != SSH_OK || key == nullptr) {
        return {};
    }
    std::string fp;
    unsigned char* hash = nullptr;
    size_t hlen = 0;
    if (ssh_get_publickey_hash(key, SSH_PUBLICKEY_HASH_SHA256, &hash, &hlen) == SSH_OK) {
        char* text = ssh_get_fingerprint_hash(SSH_PUBLICKEY_HASH_SHA256, hash, hlen);
        if (text != nullptr) {
            fp = text;
            ssh_string_free_char(text);
        }
        ssh_clean_pubkey_hash(&hash);
    }
    ssh_key_free(key);
    return fp;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_awkoo_libterminal_ssh_SshFactory_sshConnect(
    JNIEnv* env,
    jclass,
    jstring jhost,
    jint jport,
    jstring juser,
    jstring jpassword,
    jstring jprivkey_path,
    jstring jpassphrase,
    jstring jexpected_fingerprint,
    jint jtimeout_ms,
    jint jnative_fd,
    jint rows,
    jint cols) {
    const std::string host = jstring_copy(env, jhost);
    const std::string user = jstring_copy(env, juser);
    const std::string password = jstring_copy(env, jpassword);
    const std::string privkey_path = jstring_copy(env, jprivkey_path);
    const std::string passphrase = jstring_copy(env, jpassphrase);
    const std::string expected_fingerprint = jstring_copy(env, jexpected_fingerprint);
    int port = static_cast<int>(jport);
    int timeout_ms = static_cast<int>(jtimeout_ms);
    int native_fd = static_cast<int>(jnative_fd);

    // 任一失败路径：关闭 fd 即可让 App 侧流读到 EOF，会话按"进程立即退出"结束。
    if (native_fd < 0) {
        return 0;
    }

    ssh_session session = ssh_new();
    if (session == nullptr) {
        ::close(native_fd);
        return 0;
    }

    if (ssh_options_set(session, SSH_OPTIONS_HOST, host.c_str()) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_PORT, &port) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_USER, user.c_str()) != SSH_OK) {
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    if (timeout_ms > 0) {
        long seconds = timeout_ms / 1000;
        long usec = (timeout_ms % 1000) * 1000L;
        ssh_options_set(session, SSH_OPTIONS_TIMEOUT, &seconds);
        ssh_options_set(session, SSH_OPTIONS_TIMEOUT_USEC, &usec);
    }

    if (ssh_connect(session) != SSH_OK) {
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    const std::string server_fingerprint = server_fingerprint_of(session);
    if (server_fingerprint.empty()) {
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }
    if (!expected_fingerprint.empty() && server_fingerprint != expected_fingerprint) {
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    if (!privkey_path.empty()) {
        ssh_key privkey = nullptr;
        if (ssh_pki_import_privkey_file(privkey_path.c_str(), passphrase.c_str(),
                                        nullptr, nullptr, &privkey) != SSH_OK ||
            privkey == nullptr) {
            ssh_disconnect(session);
            ssh_free(session);
            ::close(native_fd);
            return 0;
        }
        const int rc = ssh_userauth_publickey(session, user.c_str(), privkey);
        ssh_key_free(privkey);
        if (rc != SSH_AUTH_SUCCESS) {
            ssh_disconnect(session);
            ssh_free(session);
            ::close(native_fd);
            return 0;
        }
    } else if (!password.empty()) {
        if (ssh_userauth_password(session, user.c_str(), password.c_str()) !=
            SSH_AUTH_SUCCESS) {
            ssh_disconnect(session);
            ssh_free(session);
            ::close(native_fd);
            return 0;
        }
    } else {
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    ssh_channel channel = ssh_channel_new(session);
    if (channel == nullptr) {
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    if (ssh_channel_open_session(channel) != SSH_OK) {
        ssh_channel_free(channel);
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    if (ssh_channel_request_pty_size(channel, "xterm-256color", cols, rows) != SSH_OK ||
        ssh_channel_request_shell(channel) != SSH_OK) {
        ssh_channel_free(channel);
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    // 后续 I/O 走非阻塞轮询（阻塞读在本实现中会直接返回 SSH_AGAIN）
    ssh_set_blocking(session, 0);

    native_ssh* h = new (std::nothrow) native_ssh();
    if (h == nullptr) {
        ssh_channel_free(channel);
        ssh_disconnect(session);
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    h->session = session;
    h->channel = channel;
    h->native_fd = native_fd;

    if (pthread_create(&h->reader, nullptr, io_reader, h) != 0) {
        teardown(h, true);
        delete h;
        return 0;
    }
    h->reader_joined = false;

    if (pthread_create(&h->writer, nullptr, io_writer, h) != 0) {
        teardown(h, true);
        delete h;
        return 0;
    }
    h->writer_joined = false;

    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_awkoo_libterminal_ssh_SshFactory_sshResize(JNIEnv*, jclass, jlong handle, jint rows, jint cols) {
    native_ssh* h = reinterpret_cast<native_ssh*>(handle);
    if (h == nullptr || h->channel == nullptr) {
        return;
    }
    ssh_channel_change_pty_size(h->channel, cols, rows);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_awkoo_libterminal_ssh_SshFactory_sshWait(JNIEnv*, jclass, jlong handle) {
    native_ssh* h = reinterpret_cast<native_ssh*>(handle);
    if (h == nullptr) {
        return -1;
    }
    // 只等输出侧线程：IO writer 读到 EOF 即代表进程退出且 exit_st 就绪。
    // io_reader 阻塞在本地 fd 读上，只能由 App 关闭 fd 解锁（close() 会先关 fdObj）；
    // 若在此 join 它，退出管线会被拖到"下一次输入"（表现为多按一次回车）。
    if (!h->writer_joined && h->writer != 0) {
        pthread_join(h->writer, nullptr);
        h->writer_joined = true;
    }
    return h->exit_st;
}

extern "C" JNIEXPORT void JNICALL
Java_com_awkoo_libterminal_ssh_SshFactory_sshKill(JNIEnv*, jclass, jlong handle) {
    native_ssh* h = reinterpret_cast<native_ssh*>(handle);
    if (h == nullptr) {
        return;
    }
    // 只置标志。io_writer 每 10ms 检查一次 killed 即退出；
    // 不动 session/channel（ssh_disconnect 在 0.12 会释放 channel，线程读取时构成 UAF）。
    h->killed = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_awkoo_libterminal_ssh_SshFactory_sshClose(JNIEnv*, jclass, jlong handle) {
    native_ssh* h = reinterpret_cast<native_ssh*>(handle);
    dispose(h);
}