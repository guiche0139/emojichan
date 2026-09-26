package com.aris.emojichan.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自定义分类。
 *
 * 表情表依然用「分类名」关联，而不是用外键 id：现有的查询、索引与 UI 全部基于名字，
 * 改成 id 需要重写所有读写路径，收益却只是省一点存储，不值得。
 *
 * 这张表存在的意义是让分类**独立续存**：某个分类下的表情被删光后，
 * 分类本身依然在列表里，不会凭空消失。
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createTime: Long = System.currentTimeMillis()
)