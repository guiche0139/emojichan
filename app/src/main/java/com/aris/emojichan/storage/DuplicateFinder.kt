package com.aris.emojichan.storage

import com.aris.emojichan.data.EmojiEntity
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * 「完全相同」检索：只有文件内容一模一样才算一组。
 *
 * 分两步，贵的放后面：
 * 1. 先按文件大小分组 —— 大小不同的两个文件不可能内容一样，这一步只看文件属性；
 * 2. 一组里有两张以上时才算 SHA-256，摘要不一样说明只是体积撞巧。
 *
 * 所以两千张图的库里真正要读盘的可能只有几十张。分组逻辑不碰 Android，读摘要的办法
 * 由调用方传进来（真机上是 [com.aris.emojichan.util.FileHash]，单测里给假数据）。
 */
object DuplicateFinder {

    /** 参与判定的一张图：体积与修改时间都按磁盘实际情况填，文件不在了就填 0。 */
    data class Item(
        val id: Long,
        val name: String,
        val path: String,
        val bytes: Long,
        val modifiedAt: Long,
        val createTime: Long
    ) {
        val present: Boolean get() = bytes > 0L
    }

    /**
     * 一组内容完全相同的图。
     *
     * [members] 按导入时间从早到晚排，第一位是默认保留的那张。
     */
    data class Group(val sha256: String, val members: List<Item>) {
        val bytesEach: Long get() = members.first().bytes

        /** 按默认选择（留最早的一张）删掉多余的之后能省下的字节。 */
        val wastedBytes: Long get() = bytesEach * (members.size - 1)
    }

    /** 按数据库记录与磁盘实际情况建一条候选。 */
    fun itemOf(emoji: EmojiEntity): Item {
        val file = File(emoji.filePath)
        val exists = file.isFile
        return Item(
            id = emoji.id,
            name = emoji.name,
            path = emoji.filePath,
            bytes = if (exists) file.length() else 0L,
            modifiedAt = if (exists) file.lastModified() else 0L,
            createTime = emoji.createTime
        )
    }

    /**
     * 值得读盘的那几张：文件还在，且同一个体积下不止它一张。
     *
     * 顺序按导入时间，让进度条走得跟列表顺序一致。
     */
    fun candidates(items: List<Item>): List<Item> = items
        .filter { it.present }
        .groupBy { it.bytes }
        .values
        .filter { it.size > 1 }
        .flatten()
        .sortedBy { it.createTime }

    /**
     * 找出所有重复组，按「能省多少」从多到少排。
     *
     * @param hashOf 读一张图的摘要；返回 null 表示读不出来（文件被删、没权限），这一张跳过。
     *   取消要照原样抛出去 —— 用户点了「停止」不能还闷头把剩下的读完。
     * @param onProgress 每处理一张报一次（已处理, 总数），总数是 [candidates] 的规模。
     */
    suspend fun find(
        items: List<Item>,
        hashOf: suspend (Item) -> String?,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Group> {
        val todo = candidates(items)
        val byHash = LinkedHashMap<String, MutableList<Item>>()
        todo.forEachIndexed { index, item ->
            onProgress(index + 1, todo.size)
            val hash = try {
                hashOf(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (!hash.isNullOrEmpty()) byHash.getOrPut(hash) { mutableListOf() }.add(item)
        }
        onProgress(todo.size, todo.size)
        return byHash
            .filterValues { it.size > 1 }
            .map { (hash, members) -> Group(hash, members.sortedBy { it.createTime }) }
            .sortedWith(
                compareByDescending<Group> { it.wastedBytes }.thenByDescending { it.members.size }
            )
    }

    /** 这一组决定留 [keepId] 时，其余该删掉的。 */
    fun redundant(group: Group, keepId: Long): List<Item> = group.members.filter { it.id != keepId }
}
