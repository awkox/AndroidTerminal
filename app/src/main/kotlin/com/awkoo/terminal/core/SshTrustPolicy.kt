package com.awkoo.terminal.core

/**
 * 主机指纹信任决策（TOFU 策略的核心，纯函数式）。
 *
 * 抽出来做成无副作用规则，UI 只负责呈现结果并按分支执行副作用
 * （写存储 / 直连 / 弹窗确认）。probe 文案约定：成功返回 "SHA256:..."；
 * 失败返回以 "ERROR: " 开头的文案（约定见 SshPreConnector.serverFingerprint）。
 */
sealed interface HostKeyDecision {

    /** 无需用户干预，直接带证连接。 */
    data class Connect(val fingerprint: String?) : HostKeyDecision

    /** 首连确认：无已存记录，向用户展示指纹请求确认。 */
    data class AskFirst(val fingerprint: String) : HostKeyDecision

    /** 指纹变更确认：已存记录与当前探测不符。 */
    data class AskChange(
        val storedFingerprint: String,
        val currentFingerprint: String
    ) : HostKeyDecision

    /** 探测失败且无已存记录可回退，直接报错。 */
    data class ProbeFailed(val message: String) : HostKeyDecision
}

object SshTrustPolicy {

    /**
     * 依据已存指纹与探测结果决定下一步动作：
     * - 探测失败但有已存记录 → 回退带已存指纹直连（native 侧严格比对兜底）；
     * - 探测失败且无记录 → [HostKeyDecision.ProbeFailed]；
     * - 无记录 → [HostKeyDecision.AskFirst]；
     * - 记录一致 → [HostKeyDecision.Connect]；
     * - 记录不符 → [HostKeyDecision.AskChange]。
     */
    fun decide(storedFingerprint: String?, probe: String): HostKeyDecision = when {
        probe.startsWith("ERROR: ") -> {
            val message = probe.removePrefix("ERROR: ")
            if (storedFingerprint != null) {
                HostKeyDecision.Connect(storedFingerprint)
            } else {
                HostKeyDecision.ProbeFailed(message)
            }
        }
        storedFingerprint == null -> HostKeyDecision.AskFirst(probe)
        storedFingerprint == probe -> HostKeyDecision.Connect(storedFingerprint)
        else -> HostKeyDecision.AskChange(storedFingerprint, probe)
    }
}