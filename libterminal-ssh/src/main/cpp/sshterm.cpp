#include <jni.h>

#include <libssh/libssh.h>

#include <poll.h>
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
    // 握手已成功且 reader/writer 线程就绪。
    volatile bool connected = false;
    // 握手失败或收到 kill：native 已释放 libssh 对象并关闭 fd（App 侧读到 EOF）。
    volatile bool connect_failed = false;
    int exit_st = -1;
    pthread_t connector = 0;
    bool connector_joined = true;
    pthread_t reader = 0;
    pthread_t writer = 0;
    bool reader_joined = true;
    bool writer_joined = true;
    // 终止唤醒通道：kill/close 写入，握手轮询线程读取。
    int abort_pipe[2] = {-1, -1};
};

struct conn_params {
    std::string host;
    std::string user;
    std::string password;
    std::string privkey_path;
    std::string passphrase;
    std::string expected_fingerprint;
    int port = 0;
    int timeout_ms = 10000;
    int native_fd = -1;
    int rows = 0;
    int cols = 0;
};

struct connector_args {
    native_ssh* h = nullptr;
    conn_params p;
};

constexpr size_t kIoBuffer = 4096;

void request_kill(native_ssh* h) {
    h->killed = true;
    if (h->abort_pipe[1] >= 0) {
        const char s = 1;
        // 单字节写入不会阻塞；忽略返回，仅供唤醒 poll。
        (void)write(h->abort_pipe[1], &s, 1);
    }
}

// 握手轮询步进：最多等 50ms，被 kill（pipe 收到数据）立即返回 false。
// 仅在返回 true 时调用方才驳回“续调非阻塞调用”。
bool round_step(native_ssh* h) {
    if (h->killed) {
        return false;
    }
    struct pollfd pfd;
    pfd.fd = h->abort_pipe[0];
    pfd.events = POLLIN;
    int rc = poll(&pfd, 1, 50);
    (void)rc;
    return !h->killed;
}

void free_channel(native_ssh* h) {
    if (h->channel != nullptr) {
        ssh_channel_free(h->channel);
        h->channel = nullptr;
    }
}

// 握手失败（含被 kill）：释放 libssh 对象并关闭 fd，让 App 侧流读到 EOF，
// 会话按“进程立即退出”结束；随后只能通过 dispose 回收线程与句柄。
void connector_fail(native_ssh* h) {
    h->killed = true;
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
    h->connect_failed = true;
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

std::string server_fingerprint_of(ssh_session session);

// 非阻塞握手状态机。各阶段（connect/auth/channel/pty/shell）在 libssh 0.12 下
// 返回“需续调”常量后，必须放弃 CPU 等待下轮数据或 kill 唤醒。
// 返回 true 表示整个握手完成；false 表示失败或已被 kill（调用方走 connector_fail）。
bool handshake_run(native_ssh* h, const conn_params& p) {
    int rc;

    // 阶段 1：TCP + SSH 协议握手。
    for (;;) {
        if (h->killed) {
            return false;
        }
        rc = ssh_connect(h->session);
        if (rc == SSH_OK) {
            break;
        }
        if (rc != SSH_AGAIN || !round_step(h)) {
            return false;
        }
    }

    // 阶段 2：服务端指纹（本地计算，不阻塞）。
    const std::string server_fingerprint = server_fingerprint_of(h->session);
    if (h->killed) {
        return false;
    }
    if (server_fingerprint.empty() ||
        (!p.expected_fingerprint.empty() &&
         server_fingerprint != p.expected_fingerprint)) {
        return false;
    }

    // 阶段 3：认证。libssh 认证失败时的返回码与续调码语义不同。
    const char* user = p.user.c_str();
    if (!p.privkey_path.empty()) {
        ssh_key privkey = nullptr;
        if (ssh_pki_import_privkey_file(p.privkey_path.c_str(),
                                        p.passphrase.c_str(), nullptr, nullptr,
                                        &privkey) != SSH_OK ||
            privkey == nullptr) {
            return false;
        }
        for (;;) {
            if (h->killed) {
                ssh_key_free(privkey);
                return false;
            }
            rc = ssh_userauth_publickey(h->session, user, privkey);
            if (rc == SSH_AUTH_SUCCESS) {
                break;
            }
            if (rc != SSH_AUTH_AGAIN || !round_step(h)) {
                ssh_key_free(privkey);
                return false;
            }
        }
        ssh_key_free(privkey);
    } else if (!p.password.empty()) {
        for (;;) {
            if (h->killed) {
                return false;
            }
            rc = ssh_userauth_password(h->session, user, p.password.c_str());
            if (rc == SSH_AUTH_SUCCESS) {
                break;
            }
            if (rc != SSH_AUTH_AGAIN || !round_step(h)) {
                return false;
            }
        }
    } else {
        return false;
    }

    // 阶段 4：打开 session 通道。
    ssh_channel channel = ssh_channel_new(h->session);
    if (channel == nullptr) {
        return false;
    }
    h->channel = channel;
    for (;;) {
        if (h->killed) {
            return false;
        }
        rc = ssh_channel_open_session(channel);
        if (rc == SSH_OK) {
            break;
        }
        if (rc != SSH_AGAIN || !round_step(h)) {
            return false;
        }
    }

    // 阶段 5：申请 PTY 与 shell。
    for (;;) {
        if (h->killed) {
            return false;
        }
        rc = ssh_channel_request_pty_size(channel, "xterm-256color", p.cols,
                                          p.rows);
        if (rc == SSH_OK) {
            break;
        }
        if (rc != SSH_AGAIN || !round_step(h)) {
            return false;
        }
    }
    for (;;) {
        if (h->killed) {
            return false;
        }
        rc = ssh_channel_request_shell(channel);
        if (rc == SSH_OK) {
            break;
        }
        if (rc != SSH_AGAIN || !round_step(h)) {
            return false;
        }
    }

    return true;
}

void* connector_entry(void* arg) {
    connector_args* a = static_cast<connector_args*>(arg);
    native_ssh* h = a->h;
    ssh_set_blocking(h->session, 0);
    const bool ok = handshake_run(h, a->p);
    delete a;

    if (!ok) {
        connector_fail(h);
        return nullptr;
    }

    if (pthread_create(&h->reader, nullptr, io_reader, h) != 0) {
        connector_fail(h);
        return nullptr;
    }
    h->reader_joined = false;

    if (pthread_create(&h->writer, nullptr, io_writer, h) != 0) {
        // 释放刚创建的 reader：关 fd 让其 read() 返回 EOF 退出。
        h->killed = true;
        if (h->native_fd >= 0) {
            close(h->native_fd);
            h->native_fd = -1;
        }
        pthread_join(h->reader, nullptr);
        h->reader_joined = true;
        connector_fail(h);
        return nullptr;
    }
    h->writer_joined = false;

    h->connected = true;
    return nullptr;
}

// 统一释放：置终止标志、按序 join connector/writer/reader，再释放 libssh 对象。
void teardown(native_ssh* h, bool join) {
    request_kill(h);
    if (join) {
        // connector 可能正在释放 libssh 对象，必须先于任何释放动作 join。
        if (!h->connector_joined && h->connector != 0 &&
            h->connector != pthread_self()) {
            pthread_join(h->connector, nullptr);
            h->connector_joined = true;
        }
        if (!h->writer_joined && h->writer != 0) {
            pthread_join(h->writer, nullptr);
            h->writer_joined = true;
        }
        // reader 阻塞在 read(native_fd)：要求调用方（Kotlin close()）先关闭
        // App 侧 fdObj 使其读到 EOF，否则此处会一直等待。
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
    if (h->abort_pipe[0] >= 0) {
        close(h->abort_pipe[0]);
        h->abort_pipe[0] = -1;
    }
    if (h->abort_pipe[1] >= 0) {
        close(h->abort_pipe[1]);
        h->abort_pipe[1] = -1;
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
        // 作为 DNS 解析等不可中断段的兜底上限。
        long seconds = timeout_ms / 1000;
        long usec = (timeout_ms % 1000) * 1000L;
        ssh_options_set(session, SSH_OPTIONS_TIMEOUT, &seconds);
        ssh_options_set(session, SSH_OPTIONS_TIMEOUT_USEC, &usec);
    }

    native_ssh* h = new (std::nothrow) native_ssh();
    if (h == nullptr) {
        ssh_free(session);
        ::close(native_fd);
        return 0;
    }

    if (pipe(h->abort_pipe) != 0) {
        ssh_free(session);
        ::close(native_fd);
        delete h;
        return 0;
    }

    connector_args* a = new (std::nothrow) connector_args();
    if (a == nullptr) {
        ssh_free(session);
        ::close(native_fd);
        ::close(h->abort_pipe[0]);
        ::close(h->abort_pipe[1]);
        delete h;
        return 0;
    }
    a->h = h;
    a->p.host = host;
    a->p.user = user;
    a->p.password = password;
    a->p.privkey_path = privkey_path;
    a->p.passphrase = passphrase;
    a->p.expected_fingerprint = expected_fingerprint;
    a->p.port = port;
    a->p.timeout_ms = timeout_ms;
    a->p.native_fd = native_fd;
    a->p.rows = rows;
    a->p.cols = cols;

    h->session = session;
    h->native_fd = native_fd;

    if (pthread_create(&h->connector, nullptr, connector_entry, a) != 0) {
        ssh_free(session);
        ::close(native_fd);
        ::close(h->abort_pipe[0]);
        ::close(h->abort_pipe[1]);
        delete a;
        delete h;
        return 0;
    }
    h->connector_joined = false;

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
    // 握手未收敛时轻量轮询等待；握手失败（含被 kill）直接返回 -1。
    while (!h->killed && !h->connected && !h->connect_failed) {
        struct pollfd pfd;
        pfd.fd = h->abort_pipe[0];
        pfd.events = POLLIN;
        int rc = poll(&pfd, 1, 10);
        (void)rc;
    }
    if (!h->connected) {
        return -1;
    }
    // 只等输出侧线程：IO writer 读到 EOF 即代表进程退出且 exit_st 就绪。
    // io_reader 阻塞在本地 fd 读上，只能由 App 关闭 fd 解锁（close() 会先关 fdObj）。
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
    // 握手期间唤醒 poll 立即折返；已连接后由 io_writer 的 killed 轮询收尾。
    // 不动 session/channel（线程仍可能正在使用它们）。
    request_kill(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_awkoo_libterminal_ssh_SshFactory_sshClose(JNIEnv*, jclass, jlong handle) {
    native_ssh* h = reinterpret_cast<native_ssh*>(handle);
    dispose(h);
}