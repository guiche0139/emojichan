package com.aris.emojichan.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 表情 ↔ 标签 的挂载表（多对多）。
 *
 * 两个外键都带 CASCADE：删表情或删标签时，SQLite 自己把挂载行清掉，
 * 不需要每个删除路径都记得手动扫一遍（漏一处就会留下野挂载）。
 */
@Entity(
    tableName = "emoji_tags",
    primaryKeys = ["emojiId", "tagId"],
    indices = [Index(value = ["emojiId"]), Index(value = ["tagId"])],
    foreignKeys = [
        ForeignKey(
            entity = EmojiEntity::class,
            parentColumns = ["id"],
            childColumns = ["emojiId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EmojiTagCrossRef(
    val emojiId: Long,
    val tagId: Long
)
