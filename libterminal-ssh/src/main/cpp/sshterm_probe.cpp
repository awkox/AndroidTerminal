// 连接前检测门面（SshInspector）的 native 侧：服务器指纹探测与私钥连通性体检。
// 与会话运行时（sshterm.cpp）分离，两者各自持有 native_ssh 之外的状态；
// 这里仅保留进程级的一次性日志捕获缓冲，供私钥导入失败诊断使用。

#include <jni.h>
#include <libssh/libssh.h>
#include <libssh/callbacks.h>

#include <pthread.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "sshterm_util.h"

namespace {

char g_pki_last_log[256] = {0};
pthread_mutex_t g_pki_log_mutex = PTHREAD_MUTEX_INITIALIZER;

void pki_log_capture(int, const char*, const char* buffer, void*) {
    if (buffer == nullptr) {
        return;
    }
    pthread_mutex_lock(&g_pki_log_mutex);
    std::strncpy(g_pki_last_log, buffer, sizeof(g_pki_last_log) - 1);
    g_pki_last_log[sizeof(g_pki_last_log) - 1] = '\0';
    pthread_mutex_unlock(&g_pki_log_mutex);
}

std::string pki_take_last_log() {
    std::string s;
    pthread_mutex_lock(&g_pki_log_mutex);
    s = g_pki_last_log;
    g_pki_last_log[0] = '\0';
    pthread_mutex_unlock(&g_pki_log_mutex);
    return s;
}

// 文件外观快照：什么都抓不到时，把这几个事实带回 App，避免继续盲猜。
std::string pki_file_meta(const char* path) {
    char line1[48] = {0};
    long size = -1;
    long body_lines = 0;
    std::FILE* f = std::fopen(path, "rb");
    if (f != nullptr) {
        std::fseek(f, 0, SEEK_END);
        size = std::ftell(f);
        std::rewind(f);
        std::fgets(line1, sizeof(line1), f);
        char buf[256];
        while (std::fgets(buf, sizeof(buf), f) != nullptr) {
            if (std::strstr(buf, "-----BEGIN") == nullptr &&
                std::strstr(buf, "-----END") == nullptr) {
                ++body_lines;
            }
        }
        std::fclose(f);
    }
    char meta[192];
    std::snprintf(meta, sizeof(meta),
                  "file=%ldB first=\"%s\" base64_lines=%ld", size, line1,
                  body_lines);
    return meta;
}

}  // namespace

// 私钥连通性校验（连接前的轻量体检）：成功返回 null，失败返回原因。
extern "C" JNIEXPORT jstring JNICALL
Java_com_awkoo_libterminal_ssh_SshInspector_sshTryLoadKey(JNIEnv* env, jclass,
                                                          jstring path_j,
                                                          jstring pass_j) {
    std::string path = jstring_copy(env, path_j);
    std::string pass = pass_j != nullptr ? jstring_copy(env, pass_j)
                                         : std::string();
    if (path.empty()) {
        return env->NewStringUTF("No key file selected");
    }
    if (::access(path.c_str(), R_OK) != 0) {
        return env->NewStringUTF("Cannot read key file");
    }

    // 失败细节只在 SSH_LOG_TRACE 及以上输出，而全局日志级别默认是 0
    // （log.c: _ssh_log 按 verbosity<=level 过滤），不调起来什么都抓不到。
    const int prev_level = ssh_get_log_level();
    ssh_set_log_level(SSH_LOG_TRACE);
    ssh_set_log_callback(pki_log_capture);
    auto import_attempt = [](const char* p, const char* pass) {
        ssh_key key = nullptr;
        int rc = ssh_pki_import_privkey_file(p, pass, nullptr, nullptr, &key);
        if (key != nullptr) {
            ssh_key_free(key);
        }
        return rc;
    };

    const char* pass_c = pass.empty() ? nullptr : pass.c_str();
    int rc = import_attempt(path.c_str(), pass_c);
    if (rc == SSH_OK) {
        ssh_set_log_level(prev_level);
        return nullptr;
    }

    // 无口令/空口令再试一次，区分"口令缺失/错误"与"格式损坏"。
    if (pass_c != nullptr) {
        int rc_plain = import_attempt(path.c_str(), nullptr);
        if (rc_plain == SSH_OK) {
            ssh_set_log_level(prev_level);
            return env->NewStringUTF("Wrong passphrase");
        }
    }

    std::string reason = pki_take_last_log();
    ssh_set_log_level(prev_level);
    if (reason.empty()) {
        const bool cb_installed = ssh_get_log_callback() == pki_log_capture;
        std::string meta = pki_file_meta(path.c_str());
        reason = "Private key import failed (no debug log, cb=" +
                 std::string(cb_installed ? "yes" : "no") + ", " + meta + ")";
    }
    return env->NewStringUTF(reason.c_str());
}

// 一次性主机探针：阻塞连接拿到服务端公钥指纹即断开。不进入现有会话状态机，
// 供 App 在首连 TOFU 确认时先取指纹再决定是否信任。成功返回 "SHA256:..."；
// 失败返回 "ERROR: <原因>"。
extern "C" JNIEXPORT jstring JNICALL
Java_com_awkoo_libterminal_ssh_SshInspector_sshGetServerFingerprint(
    JNIEnv* env, jclass, jstring jhost, jint jport, jint jtimeout_ms) {
    const std::string host = jstring_copy(env, jhost);
    if (host.empty()) {
        return env->NewStringUTF("ERROR: empty host");
    }
    int port = static_cast<int>(jport);
    int timeout_ms = static_cast<int>(jtimeout_ms);

    ssh_session session = ssh_new();
    if (session == nullptr) {
        return env->NewStringUTF("ERROR: ssh_new failed");
    }
    int strict = 0;  // 探测阶段不做校验，仅读取指纹
    bool ok = ssh_options_set(session, SSH_OPTIONS_HOST, host.c_str()) == SSH_OK &&
              ssh_options_set(session, SSH_OPTIONS_PORT, &port) == SSH_OK &&
              ssh_options_set(session, SSH_OPTIONS_STRICTHOSTKEYCHECK, &strict) == SSH_OK;
    if (ok && timeout_ms > 0) {
        long seconds = timeout_ms / 1000;
        long usec = (timeout_ms % 1000) * 1000L;
        ok = ssh_options_set(session, SSH_OPTIONS_TIMEOUT, &seconds) == SSH_OK &&
             ssh_options_set(session, SSH_OPTIONS_TIMEOUT_USEC, &usec) == SSH_OK;
    }
    jstring result = nullptr;
    if (ok && ssh_connect(session) == SSH_OK) {
        const std::string fp = server_fingerprint_of(session);
        result = fp.empty() ? env->NewStringUTF("ERROR: fingerprint unavailable")
                            : env->NewStringUTF(fp.c_str());
    } else {
        const char* err = ssh_get_error(session);
        const std::string text = "ERROR: " +
            std::string(err != nullptr ? err : "connection failed");
        result = env->NewStringUTF(text.c_str());
    }
    ssh_disconnect(session);
    ssh_free(session);
    return result;
}