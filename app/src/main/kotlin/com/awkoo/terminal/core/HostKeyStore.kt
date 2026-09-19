package com.awkoo.terminal.core

import android.content.Context

/**
 * SSH 服务端主机密钥指纹存储（首连 TOFU 的持久化层）。
 *
 * 键为 "host:port"，值为 OpenSSH 风格 "SHA256:..." 指纹。首次连接经用户确认的
 * 指纹写入此处；后续连接由 native 与 [com.awkoo.libterminal.ssh.SshInfo] 携带的
 * 期望指纹严格比对，不匹配即中止，防止中间人攻击。
 */
class HostKeyStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE)

    fun get(host: String, port: Int): String? {
        return prefs.getString(key(host, port), null)
    }

    fun put(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    fun remove(host: String, port: Int) {
        prefs.edit().remove(key(host, port)).apply()
    }

    private fun key(host: String, port: Int) = "$host:$port"
}