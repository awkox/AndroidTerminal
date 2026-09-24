package com.awkoo.terminal.core

import kotlinx.serialization.Serializable

/**
 * SSH 认证方式（与连接对话框选择一致，提升为核心共享并支持序列化）。
 */
@Serializable
enum class SshAuthMode { Password, PrivateKey }

/**
 * 上一次 SSH 连接参数快照（单记录覆盖写，只记最后一次连接）。
 *
 * 凭据策略：默认不持久化密码与私钥口令，仅当用户勾选对应"记住"开关后才落盘；
 * 私钥路径 [keyPath] 指向 app 私有目录下的导入文件，重启后通常仍有效，
 * 由 UI 在回填时探测文件是否仍存在，失效则让用户重新选择。
 */
@Serializable
data class LastSshConnection(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val authMode: SshAuthMode = SshAuthMode.Password,
    val rememberPassword: Boolean = false,
    val password: String? = null,
    val keyPath: String? = null,
    val rememberKeyPassphrase: Boolean = false,
    val keyPassphrase: String? = null
)