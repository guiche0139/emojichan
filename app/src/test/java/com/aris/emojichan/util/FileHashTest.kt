package com.aris.emojichan.util

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内容摘要（「完全相同」检索的第一块地基）。
 *
 * 用公开的 SHA-256 测试向量断言，而不是「算两遍看结果一样」—— 后者只能证明算法稳定，
 * 证明不了算出来的确实是 SHA-256。跨缓冲那一张是防「读满一块漏一块」的老问题。
 */
class FileHashTest {

    private fun tempFile(content: ByteArray): File =
        File.createTempFile("filehash", ".bin").apply {
            writeBytes(content)
            deleteOnExit()
        }

    private fun tempFile(text: String): File = tempFile(text.toByteArray())

    @Test
    fun emptyFileMatchesKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FileHash.sha256(tempFile(""))
        )
    }

    @Test
    fun abcMatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileHash.sha256(tempFile("abc"))
        )
    }

    /** 比内部缓冲（64 KB）更长，且不是它的整数倍。 */
    @Test
    fun hashesAcrossBufferBoundaries() {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, FileHash.sha256(tempFile(content)))
    }

    @Test
    fun identicalContentHashesSameAndDifferentContentDoesNot() {
        assertEquals(FileHash.sha256(tempFile("猫")), FileHash.sha256(tempFile("猫")))
        assertNotEquals(FileHash.sha256(tempFile("猫")), FileHash.sha256(tempFile("狗")))
    }

    @Test
    fun missingFileReturnsNull() {
        val gone = File(File(System.getProperty("java.io.tmpdir")!!), "emojichan-没有这个文件")
        assertNull(FileHash.sha256(gone))
    }

    /** 目录也读不出摘要：宁可返回 null 让调用方跳过，也不要抛出去把整轮比对打断。 */
    @Test
    fun directoryReturnsNull() {
        assertNull(FileHash.sha256(File(System.getProperty("java.io.tmpdir")!!)))
    }
}
