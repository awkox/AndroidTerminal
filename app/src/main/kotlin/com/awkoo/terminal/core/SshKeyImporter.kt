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
 * 或粘贴文本都必须落到 app 私有目录 [dirName] 后转为真实路径再使用。
 */
object SshKeyImporter {

    private const val dirName = "ssh"

    /** 导入结果：成功时 [path] 非空；失败时 [error] 给出具体原因。 */
    data class ImportResult(val path: String?, val error: String?)

    fun ok(path: String) = ImportResult(path, null)

    fun fail(error: String?) = ImportResult(null, error)

    /**
     * SAF 文件导入。读取失败/为空/复制异常时返回带原因的结果。
     */
    fun import(context: Context, uri: Uri?): ImportResult {
        if (uri == null) {
            return fail("No file selected")
        }
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val target = File(dir, "key_${System.currentTimeMillis()}")
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return fail("Cannot open selected file (provider returned no stream)")
            input.use {
                target.outputStream().use { out ->
                    val copied = it.copyTo(out)
                    if (copied <= 0) {
                        target.delete()
                        return fail("Selected file is empty")
                    }
                    out.flush()
                }
            }
            // fsync 落盘：导入完成后即便进程被杀也不丢密钥。
            runCatching {
                FileOutputStream(target).use { out ->
                    out.fd.sync()
                }
            }
            ok(target.absolutePath)
        } catch (e: Exception) {
            target.delete()
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 将粘贴的私钥文本落盘为真实路径（绕过 SAF），供 native 直接加载。
     * 返回复制后的路径；内容为空或写入失败返回 null。
     */
    fun importText(context: Context, content: String?): ImportResult {
        val cleaned = content?.trim() ?: return fail("No key content")
        if (cleaned.isEmpty()) {
            return fail("No key content")
        }
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val target = File(dir, "pasted_${System.currentTimeMillis()}")
        return try {
            target.writeText(cleaned, Charsets.UTF_8)
            runCatching {
                FileOutputStream(target).use { out -> out.fd.sync() }
            }
            ok(target.absolutePath)
        } catch (e: Exception) {
            target.delete()
            fail(e.message ?: e.javaClass.simpleName)
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