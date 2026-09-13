package com.awkoo.libterminal.ssh

/**
 * SSH 连接配置（协议参数）。
 *
 * 仅描述"连到哪、如何认证"，不含任何持久化/加密假设。敏感凭据
 * （口令、私钥口令）通过 [SshCredentials] 由调用方解密后在调用时
 * 当场提供，存储与加密策略交由使用方自行完成。
 */
data class SshInfo(
    val name: String? = null,
    val host: String,
    val port: Int = 22,
    val user: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val keyPath: String? = null,
    /** 期望的主机密钥指纹（OpenSSH 风格 "SHA256:..."）；null 表示尚未信任（TOFU）。 */
    val hostKeyFingerprint: String? = null,
    val keepAliveMs: Int = 0,
    val timeoutMs: Int = 10000
)

/** 认证方式。 */
enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY
}

/**
 * 运行期明文凭据。
 *
 * 由调用方从自己的存储/加密策略中解密后当场提供；本模块从不落盘，
 * 也不持有任何密钥。
 */
data class SshCredentials(
    val password: String? = null,
    val keyPassphrase: String? = null
)