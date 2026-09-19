package com.awkoo.libterminal.ssh

/**
 * 认证方式（sealed 保证恰好一种合法形态）。
 *
 * 运行期明文凭据直接携带在 [SshAuth] 中，由调用方现造后当场使用；
 * 本模块不做任何持久化/加密假设。
 */
sealed interface SshAuth {
    /** 密码认证。 */
    data class Password(val password: String) : SshAuth

    /** 私钥认证；私钥加密时需 [passphrase]。 */
    data class PrivateKey(val path: String, val passphrase: String? = null) : SshAuth
}

/**
 * SSH 连接配置（与 libterminal-pty 的 CommandInfo 对应）。
 *
 * 仅描述"连到哪、如何认证"；hostKeyFingerprint 为 OpenSSH 风格
 * "SHA256:..." 的期望服务端指纹，null 表示首次信任（TOFU），
 * 非 null 时严格比对，不匹配则连接失败（表现为进程立即退出）。
 */
data class SshInfo(
    val name: String? = null,
    val host: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
    val hostKeyFingerprint: String? = null,
    val timeoutMs: Int = 10000
)