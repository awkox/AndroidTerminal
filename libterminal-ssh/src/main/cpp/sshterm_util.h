#pragma once

// 会话运行时（sshterm.cpp）与连接前探测（sshterm_probe.cpp）共享的小工具。
// 头文件放在匿名命名空间内，保证每个 TU 各自实例一份（内部链接），避免
// 跨 TU 重复符号与 ODR 问题。

#include <jni.h>
#include <libssh/libssh.h>

#include <string>

namespace {

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