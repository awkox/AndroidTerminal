package com.awkoo.terminal.core

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sshDataStore by preferencesDataStore("SshInfo")

@Serializable
enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY
}

/**
 * 持久化的 SSH 连接配置（进程参数载体，与 [CommandInfo] 对应）。
 *
 * 敏感字段（密码、私钥口令）只保存 [CredentialCipher] 加密后的密文；
 * 已知会话指纹存于 [hostKeyFingerprint]，为 null 表示尚未做首次信任（TOFU）。
 */
@Serializable
data class SshInfo(
    val id: Long = 0,
    val name: String? = null,
    val host: String,
    val port: Int = 22,
    val user: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val passwordCipher: String? = null,
    val keyPath: String? = null,
    val keyPassphraseCipher: String? = null,
    val hostKeyFingerprint: String? = null,
    val keepAliveMs: Int = 0,
    val timeoutMs: Int = 10000
)

/**
 * SSH 连接配置持久化存储。
 *
 * 依赖既有的 Preferences DataStore + ProtoBuf 模式（同 [com.awkoo.terminal.extrakeys.ExtraKeysConfig]），
 * 独立文件 [SshInfo]，损坏条目（解码失败）自动跳过。
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class SshInfoStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val datastore = context.sshDataStore

    /** 全部连接配置，按 id 升序。损坏条目被剔除。 */
    val sshInfoList: Flow<List<SshInfo>> = datastore.data.map { prefs ->
        prefs.asMap()
            .filterKeys { it.name.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? String)?.let { decode(it) }
            }
            .sortedBy { it.id }
    }

    suspend fun get(id: Long): SshInfo? {
        val list = sshInfoList.first()
        return list.firstOrNull { it.id == id }
    }

    /** 分配自增 id 后落盘，返回带 id 的副本。 */
    suspend fun insert(input: SshInfo): SshInfo {
        var assigned = input.id
        datastore.edit { prefs ->
            if (input.id <= 0) {
                assigned = (prefs[nextIdKey] ?: 1).toLong()
                prefs[nextIdKey] = assigned.toInt() + 1
            }
            prefs[infoKey(assigned)] = encode(input.copy(id = assigned))
        }
        return input.copy(id = assigned)
    }

    suspend fun upsert(info: SshInfo) {
        if (info.id <= 0) {
            insert(info)
            return
        }
        datastore.edit { prefs ->
            prefs[infoKey(info.id)] = encode(info)
        }
    }

    suspend fun delete(id: Long) {
        datastore.edit { prefs ->
            prefs.remove(infoKey(id))
        }
    }

    private fun infoKey(id: Long) = stringPreferencesKey(KEY_PREFIX + id)

    private fun encode(info: SshInfo): String =
        Base64.encodeToString(ProtoBuf.encodeToByteArray(info), Base64.NO_WRAP)

    private fun decode(encoded: String): SshInfo? =
        try {
            ProtoBuf.decodeFromByteArray<SshInfo>(
                Base64.decode(encoded, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val KEY_PREFIX = "info_"
        private val nextIdKey = intPreferencesKey("nextInfoId")
    }
}