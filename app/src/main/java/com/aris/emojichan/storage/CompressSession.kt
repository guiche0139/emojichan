package com.aris.emojichan.storage

import java.io.File

/** 一张压好了、等用户确认的结果（[compressed] 还在缓存目录里，没进表情库）。 */
data class PendingCompress(
    val id: Long,
    val name: String,
    val original: String,
    val originalBytes: Long,
    val compressed: String,
    val compressedBytes: Long,
    val width: Int,
    val height: Int
) {
    val savedBytes: Long get() = (originalBytes - compressedBytes).coerceAtLeast(0L)
}

/**
 * 压缩页与预览页之间的中转站。
 *
 * 结果里有路径和尺寸，用 Intent 传要写 Parcelable，而这两页本来就同生共死，放在进程里更省事。
 * 预览页被回收（比如用户从最近任务里划掉）时留在缓存里的临时文件，下次进压缩页会一起清掉。
 */
object CompressSession {

    private val pending = mutableListOf<PendingCompress>()

    // 这几把锁是补上来的：压缩页在 IO 线程上算完结果才 put，而预览页（同一个进程、同一个
    // object）随时可能 drop 或 clear，交错时 ArrayList 会抛 ConcurrentModificationException
    // 或者干脆丢一次结果（emc-2-032）。临界区都只有几个赋值，锁的代价可以忽略。
    @Synchronized
    fun put(items: List<PendingCompress>) {
        pending.clear()
        pending += items
    }

    @Synchronized
    fun items(): List<PendingCompress> = pending.toList()

    @get:Synchronized
    val savedBytes: Long get() = pending.sumOf { it.savedBytes }

    @Synchronized
    fun clear() {
        pending.clear()
    }

    /** 放弃这次结果：把缓存里的压缩文件删掉，原图一张不动。 */
    @Synchronized
    fun drop() {
        pending.forEach { runCatching { File(it.compressed).delete() } }
        pending.clear()
    }
}
