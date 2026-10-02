package com.aris.emojichan.util

import java.io.File
import java.security.MessageDigest

/**
 * 文件内容的摘要（SHA-256）。
 *
 * 重复检索要的是「两个文件是不是一模一样的字节」，所以用密码学摘要而不是感知哈希：
 * 改一个像素结果就完全不同，不存在「差不多」的余地。JDK 自带 [MessageDigest]，
 * 不引第三方库。
 *
 * 边读边喂摘要，不把整个文件读进内存 —— 表情库里几十 MB 的长截图也有。
 */
object FileHash {

    private const val BUFFER_BYTES = 64 * 1024

    /** @return 小写十六进制摘要；文件不在了、读不动（没权限、正被写）时返回 null。 */
    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    } catch (e: Exception) {
        null
    }
}
