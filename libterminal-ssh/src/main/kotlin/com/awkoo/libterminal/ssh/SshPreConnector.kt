package com.awkoo.libterminal.ssh

/**
 * 连接前检测门面：与进程运行时 [SshFactory] 分离。
 *
 * 两者共享同一 native 库，但职责不同——[SshFactory] 管会话生命周期
 * （connect/resize/wait/kill/close），这里只管"连接之前"的一次性检查
 * （私钥连通性、服务器指纹探测）。拆开后 [SshFactory] 不再承担纯检测
 * 职责，也便于上层把本接口替换为可注入的假实现。
 */
interface SshPreConnector {

    /**
     * 校验私钥可被加载：返回 null 表示可加载；否则为失败原因文本
     * （找不到/不可读/passphrase 不匹配/格式不支持）。
     */
    fun checkPrivateKey(path: String, passphrase: String?): String?

    /**
     * 服务器指纹探测（首连 TOFU 用）：返回 "SHA256:..." 指纹；
     * 失败时返回以 "ERROR: " 开头的文案。
     */
    fun serverFingerprint(host: String, port: Int, timeoutMs: Int): String
}

/** [SshPreConnector] 的 native 实现。 */
object SshInspector : SshPreConnector {

    init { System.loadLibrary("sshterm") }

    @JvmStatic
    private external fun sshTryLoadKey(path: String, passphrase: String?): String?

    @JvmStatic
    private external fun sshGetServerFingerprint(
        host: String,
        port: Int,
        timeoutMs: Int
    ): String

    override fun checkPrivateKey(path: String, passphrase: String?): String? {
        return sshTryLoadKey(path, passphrase)
    }

    override fun serverFingerprint(host: String, port: Int, timeoutMs: Int): String {
        return sshGetServerFingerprint(host, port, timeoutMs)
    }
}