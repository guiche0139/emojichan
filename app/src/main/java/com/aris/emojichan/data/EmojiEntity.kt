package com.aris.emojichan.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "emojis",
    indices = [
        Index(value = ["category"]),
        Index(value = ["source"]),
        Index(value = ["isFavorite"])
    ]
)
data class EmojiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filePath: String,
    val fileType: String,
    val category: String = "默认",
    val tags: String = "",
    val source: String = "local",
    val isFavorite: Boolean = false,
    val usageCount: Int = 0,
    val lastUsedTime: Long = 0L,
    val createTime: Long = System.currentTimeMillis(),
    val fileSize: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)
