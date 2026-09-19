package com.awkoo.terminal.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * SSH 私钥导入器。
 *
 * native 侧 [com.awkoo.libterminal.ssh.SshFactory] 通过真实文件路径加载私钥
 * （ssh_pki_import_privkey_file，fopen 语义），因此 SAF 选中的 content:// URI
 * 必须复制到 app 私有目录 [dirName] 后转为真实路径再使用。
 */
object SshKeyImporter {

    private const val dirName = "ssh"

    /**
     * 返回复制后的真实路径；读取失败或内容为空返回 null。
     */
    fun import(context: Context, uri: Uri?): String? {
        if (uri == null) {
            return null
        }
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val target = File(dir, "key_${System.currentTimeMillis()}")
        val input = context.contentResolver.openInputStream(uri)
            ?: return null
        return try {
            input.use { ins ->
                FileOutputStream(target).use { out ->
                    val copied = ins.copyTo(out)
                    if (copied <= 0) {
                        target.delete()
                        return null
                    }
                    out.flush()
                    // fsync 落盘：必须在同一个写入流上 force，否则重新打开
                    // FileOutputStream 会 O_TRUNC 把刚复制的内容截成 0 字节。
                    out.channel.force(true)
                }
            }
            target.absolutePath
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** 会话不再使用后清理单个密钥文件与空目录。 */
    fun delete(keyPath: String?) {
        if (keyPath == null) {
            return
        }
        val f = File(keyPath)
        if (f.parentFile?.name == dirName) {
            f.delete()
        }
    }
}