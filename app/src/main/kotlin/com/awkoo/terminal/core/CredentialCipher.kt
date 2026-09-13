package com.awkoo.terminal.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH 凭据（口令/私钥口令）的落盘加密器。
 *
 * 使用 Android Keystore 中生成的不可导出 AES/GCM 密钥；密文以
 * Base64(iv || ciphertext) 存储，只有本应用可解密。KeyStore 密钥丢失
 * （设备重置/恢复）或数据损坏时 [decrypt] 返回 null，调用方须让用户重输。
 */
@Singleton
class CredentialCipher @Inject constructor() {

    @Volatile
    private var secretKey: SecretKey? = null

    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, loadKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.encodeToByteArray())
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun decrypt(payload: String): String? {
        if (payload.isEmpty()) return null
        return try {
            val raw = Base64.decode(payload, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_LEN_BITS, iv))
            String(cipher.doFinal(ct))
        } catch (e: Exception) {
            null
        }
    }

    private fun loadKey(): SecretKey {
        secretKey?.let { return it }
        synchronized(this) {
            secretKey?.let { return it }
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
                secretKey = it
                return it
            }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return generator.generateKey().also { secretKey = it }
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "terminal_ssh_credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_LEN_BITS = 128
    }
}