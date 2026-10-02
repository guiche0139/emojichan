package com.aris.emojichan.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一张表情的内容指纹（v0.1.409 新增，重复检索用）。
 *
 * 只回答「这张图的内容长什么样」，不存图本身。两项特征各服务一个页面：
 * - [sha256] 给「完全相同」用：先比 [bytes]，大小不同的两个文件不可能内容一样，这一步
 *   不用读盘；大小一样才去算摘要，摘要相同才算一组；
 * - [dhash] 给「相似」用：64 位感知哈希，跟组代表差几位就是「有多像」，没有非黑即白。
 *
 * 两列都可以为空：哪个页面先跑就先算哪一项，另一项留着，等它自己被用到时再算。
 *
 * 这张表既是判定依据，也是缓存 —— 指纹算过一次就留着，第二次进重复页不用再把全库
 * 文件读一遍。[modifiedAt] 一起存是为了判断旧指纹还作不作数：文件被替换过（压缩替换、
 * 同名覆盖导入）体积可能碰巧一样，修改时间几乎不可能一样，两个都对得上才复用。
 *
 * 外键挂在 emojis 上并 ON DELETE CASCADE：表情记录没了，指纹行跟着消失，不留孤儿。
 */
@Entity(
    tableName = "image_features",
    foreignKeys = [
        ForeignKey(
            entity = EmojiEntity::class,
            parentColumns = ["id"],
            childColumns = ["emojiId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["emojiId"]),
        Index(value = ["sha256"])
    ]
)
data class ImageFeatureEntity(
    @PrimaryKey val emojiId: Long,
    val bytes: Long,
    val modifiedAt: Long,
    /** 内容摘要（「完全相同」用）；还没算过就是 null。 */
    val sha256: String? = null,

    /** 感知哈希（「相似」用，v0.1.409 加）；还没算过就是 null。 */
    val dhash: Long? = null,

    val computedAt: Long = System.currentTimeMillis()
)
