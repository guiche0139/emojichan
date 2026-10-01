package com.aris.emojichan.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签。
 *
 * 和包（categories）是两套东西：一张表情只属于一个包，但可以挂任意多个标签。
 * 表情与标签的挂载关系存在 [EmojiTagCrossRef] 里，不用逗号分隔的字符串 ——
 * 字符串方案没法做「同时满足两个标签」这种查询，改名/删标签也要逐行解析。
 *
 * 这张表独立续存：某个标签下的表情被删光后，标签本身还在，不会凭空消失。
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createTime: Long = System.currentTimeMillis()
)
