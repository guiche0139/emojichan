package com.aris.emojichan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** 一条 IN 里最多塞多少个占位符：Android ≤11 的 SQLite 变量上限是 999，留点余量（emc-2-032）。 */
private const val SQLITE_IN_CHUNK = 900

@Dao
interface EmojiDao {
    @Query("SELECT * FROM emojis ORDER BY createTime DESC")
    fun getAllEmojis(): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE isFavorite = 1 ORDER BY createTime DESC")
    fun getFavorites(): Flow<List<EmojiEntity>>

    /**
     * 统一过滤入口：收藏 / 关键词 / 标签 的任意组合都由 [EmojiQuery.build] 拼成
     * 一条 SQL 传进来。
     *
     * observedEntities 必须把两张标签表也列上：改标签只动 emoji_tags 或 tags，
     * 不列它们的话 Room 不会重发列表，用户点了标签筛选看不到变化。
     */
    @RawQuery(observedEntities = [EmojiEntity::class, EmojiTagCrossRef::class, TagEntity::class])
    fun observeFiltered(query: SupportSQLiteQuery): Flow<List<EmojiEntity>>

    /**
     * 过滤查询的一次性版本（测试、发送面板的标签筛选用）。
     *
     * suspend 不是为了好看：Room 对非 suspend 的一次性查询会做「主线程不许碰数据库」检查，
     * 悬浮球面板就是在主线程上调它的 —— 直接抛 IllegalStateException 把应用干崩（emc-2-009）。
     */
    @RawQuery(observedEntities = [EmojiEntity::class, EmojiTagCrossRef::class, TagEntity::class])
    suspend fun findFiltered(query: SupportSQLiteQuery): List<EmojiEntity>

    @Insert
    suspend fun insert(emoji: EmojiEntity): Long

    @Insert
    suspend fun insertAll(emojis: List<EmojiEntity>)

    @Delete
    suspend fun delete(emoji: EmojiEntity)

    @Query("DELETE FROM emojis WHERE id IN (:ids)")
    suspend fun deleteByIdsRaw(ids: List<Long>)

    /** 大库「全选后删除」一次塞进来的 id 远超 SQLite 变量上限，按块拆开（emc-2-032）。 */
    @Transaction
    suspend fun deleteByIds(ids: List<Long>) {
        ids.chunked(SQLITE_IN_CHUNK).forEach { deleteByIdsRaw(it) }
    }

    @Query("SELECT filePath FROM emojis WHERE id IN (:ids)")
    suspend fun getFilePathsByIdsRaw(ids: List<Long>): List<String>

    @Transaction
    suspend fun getFilePathsByIds(ids: List<Long>): List<String> =
        ids.chunked(SQLITE_IN_CHUNK).flatMap { getFilePathsByIdsRaw(it) }

    /** 数据库当前引用的全部文件路径，用于清理孤儿文件。 */
    @Query("SELECT filePath FROM emojis")
    suspend fun getAllFilePaths(): List<String>

    /** 库里已有的全部表情名，导入时用来去重。 */
    @Query("SELECT name FROM emojis")
    suspend fun getAllNames(): List<String>

    @Query("SELECT * FROM emojis WHERE id IN (:ids)")
    suspend fun getByIdsRaw(ids: List<Long>): List<EmojiEntity>

    @Transaction
    suspend fun getByIds(ids: List<Long>): List<EmojiEntity> =
        ids.chunked(SQLITE_IN_CHUNK).flatMap { getByIdsRaw(it) }

    /** @return 实际更新的行数，0 表示该 id 已不存在。 */
    @Query("UPDATE emojis SET name = :newName WHERE id = :id")
    suspend fun rename(id: Long, newName: String): Int

    @Update
    suspend fun update(emoji: EmojiEntity)

    /** @return 实际更新的行数，0 表示该 id 已不存在。 */
    @Query("UPDATE emojis SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean): Int

    @Query("SELECT * FROM emojis WHERE id = :id")
    fun observeById(id: Long): Flow<EmojiEntity?>

    @Query("SELECT * FROM emojis WHERE id = :id")
    suspend fun getById(id: Long): EmojiEntity?

    @Query("SELECT COUNT(*) FROM emojis")
    fun getCount(): Flow<Int>

    /**
     * 最近用过的表情，供发送面板的「最近」分组使用。
     *
     * 过滤 lastUsedTime > 0 而不是按 usageCount：lastUsedTime 为 0 表示从未发送过，
     * 这类记录按时间倒序排会全部堆在最前面（默认值 0 最小，反序后反而最靠后，
     * 但语义上它们不该出现在「最近」里）。
     */
    @Query("SELECT * FROM emojis WHERE lastUsedTime > 0 ORDER BY lastUsedTime DESC LIMIT :limit")
    fun getRecentEmojis(limit: Int): Flow<List<EmojiEntity>>

    // ---------- 导入 ----------

    /**
     * 导入一张表情，名字与自动标签都在同一个事务里落库：
     * 重名时用 [EmojiNaming.unique] 加 (1)(2)…，再按 [autoTagName] 挂上「图片」/「动图」。
     *
     * 必须是事务：否则两个导入协程可能各自读到「名字没被占用」，落库后出现两个同名表情。
     *
     * @return 新行的 id。
     */
    @Transaction
    suspend fun importEmoji(emoji: EmojiEntity, autoTagName: String?): Long {
        val unique = EmojiNaming.unique(emoji.name, getAllNames())
        val id = insert(emoji.copy(name = unique))
        if (id > 0 && autoTagName != null) {
            val tagId = ensureTag(autoTagName)
            if (tagId != null) insertTagLinks(listOf(EmojiTagCrossRef(id, tagId)))
        }
        return id
    }

    // ---------- 标签 ----------

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeTags(): Flow<List<TagEntity>>

    /**
     * 每个标签挂着多少张表情（标签下拉排序用，用户 m11668 第 7 条）。
     *
     * 走 emoji_tags 表：挂载行一变 Room 就重发一次，所以贴过 / 摘过标签之后
     * 面板的顺序会跟着变。没挂过任何东西的标签在这里查不到，调用方按 0 张处理。
     */
    @Query("SELECT tagId, COUNT(*) AS count FROM emoji_tags GROUP BY tagId")
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun getTags(): List<TagEntity>

    /** @return 新行的 id；-1 表示同名标签已存在。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getTagByName(name: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagById(id: Long): TagEntity?

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTagRow(id: Long): Int

    /** 所有标签名（文件夹导入给新标签去重时用）。 */
    @Query("SELECT name FROM tags")
    suspend fun getAllTagNames(): List<String>

    /** 重命名标签。@return 实际更新的行数，0 表示该标签已不存在。 */
    @Query("UPDATE tags SET name = :newName WHERE id = :id")
    suspend fun renameTag(id: Long, newName: String): Int

    /** 标签不存在就建一个。@return 标签 id；null 表示同名冲突且回查也失败。 */
    @Transaction
    suspend fun ensureTag(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        getTagByName(trimmed)?.let { return it.id }
        val inserted = insertTag(TagEntity(name = trimmed))
        return if (inserted != -1L) inserted else getTagByName(trimmed)?.id
    }

    /**
     * 删除标签本身，连带摘掉它在所有表情上的挂载。
     *
     * 外键上是 ON DELETE CASCADE，SQLite 自己会清挂载行；这里仍然显式删一遍：
     * 一旦哪天外键约束没生效（老库、异常升级），残留的挂载行会让标签计数对不上。
     *
     * @return false 表示标签不存在。
     */
    @Transaction
    suspend fun deleteTagEverywhere(tagId: Long): Boolean {
        getTagById(tagId) ?: return false
        deleteTagLinksOfTag(tagId)
        return deleteTagRow(tagId) > 0
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinks(links: List<EmojiTagCrossRef>)

    /** 批量摘标签（表情 × 标签 的笛卡尔积）。 */
    @Query("DELETE FROM emoji_tags WHERE emojiId IN (:emojiIds) AND tagId IN (:tagIds)")
    suspend fun deleteTagLinksRaw(emojiIds: List<Long>, tagIds: List<Long>): Int

    /** 两个 IN 都要拆：占位符总数是「表情数 + 标签数」，两边都可能爆（emc-2-032）。 */
    @Transaction
    suspend fun deleteTagLinks(emojiIds: List<Long>, tagIds: List<Long>): Int {
        var removed = 0
        emojiIds.chunked(SQLITE_IN_CHUNK).forEach { emojiChunk ->
            tagIds.chunked(SQLITE_IN_CHUNK).forEach { tagChunk ->
                removed += deleteTagLinksRaw(emojiChunk, tagChunk)
            }
        }
        return removed
    }

    @Query("DELETE FROM emoji_tags WHERE emojiId IN (:emojiIds)")
    suspend fun deleteTagLinksOfRaw(emojiIds: List<Long>): Int

    @Transaction
    suspend fun deleteTagLinksOf(emojiIds: List<Long>): Int {
        var removed = 0
        emojiIds.chunked(SQLITE_IN_CHUNK).forEach { removed += deleteTagLinksOfRaw(it) }
        return removed
    }

    @Query("DELETE FROM emoji_tags WHERE tagId = :tagId")
    suspend fun deleteTagLinksOfTag(tagId: Long): Int

    @Query("SELECT tagId FROM emoji_tags WHERE emojiId = :emojiId")
    fun observeTagIdsOf(emojiId: Long): Flow<List<Long>>

    @Query(
        "SELECT t.* FROM tags t INNER JOIN emoji_tags l ON l.tagId = t.id " +
            "WHERE l.emojiId = :emojiId ORDER BY t.name"
    )
    fun observeTagsOf(emojiId: Long): Flow<List<TagEntity>>

    @Query(
        "SELECT t.* FROM tags t INNER JOIN emoji_tags l ON l.tagId = t.id " +
            "WHERE l.emojiId = :emojiId ORDER BY t.name"
    )
    suspend fun getTagsOf(emojiId: Long): List<TagEntity>

    /** 批量打标签。@return 新挂上的条数（已存在的组合会被 IGNORE 掉，不算在内）。 */
    @Transaction
    suspend fun addTagsTo(emojiIds: List<Long>, tagIds: List<Long>): Int {
        if (emojiIds.isEmpty() || tagIds.isEmpty()) return 0
        val links = ArrayList<EmojiTagCrossRef>(emojiIds.size * tagIds.size)
        for (emojiId in emojiIds) {
            for (tagId in tagIds) links.add(EmojiTagCrossRef(emojiId, tagId))
        }
        insertTagLinks(links)
        return links.size
    }

    /** 批量摘标签。@return 实际删掉的条数。 */
    @Transaction
    suspend fun removeTagsFrom(emojiIds: List<Long>, tagIds: List<Long>): Int {
        if (emojiIds.isEmpty() || tagIds.isEmpty()) return 0
        return deleteTagLinks(emojiIds, tagIds)
    }

    /** 把一张表情的标签集合整体换成 [tagIds]（详情页编辑用）。 */
    @Transaction
    suspend fun setTagsOf(emojiId: Long, tagIds: List<Long>) {
        deleteTagLinksOf(listOf(emojiId))
        if (tagIds.isNotEmpty()) {
            insertTagLinks(tagIds.map { EmojiTagCrossRef(emojiId, it) })
        }
    }

    /** 记录一次发送：次数 +1、时间戳刷新。@return 实际更新的行数，0 表示该 id 已不存在。 */
    @Query("UPDATE emojis SET usageCount = usageCount + 1, lastUsedTime = :time WHERE id = :id")
    suspend fun recordUsage(id: Long, time: Long): Int

    /** 删除表情（连带它在 emoji_tags 里的挂载）。 */
    @Transaction
    suspend fun deleteEmojisByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        deleteTagLinksOf(ids)
        deleteByIds(ids)
    }

    // ---------- 存储管理 ----------

    /**
     * 压缩替换：只换文件相关的几列。
     *
     * 名字、标签、收藏、使用次数都不动 —— 压缩对用户来说只是「这张图小了」，
     * 不该顺手把它变成一张新表情。
     */
    @Query(
        "UPDATE emojis SET filePath = :filePath, fileType = :fileType, " +
            "fileSize = :fileSize, width = :width, height = :height WHERE id = :id"
    )
    suspend fun updateCompressed(
        id: Long,
        filePath: String,
        fileType: String,
        fileSize: Long,
        width: Int,
        height: Int
    )

    // ---------- 表情库打包导出 / 导入 ----------

    /** 全库一次性读出来（打包导出用；Flow 那条是给界面订阅的）。 */
    @Query("SELECT * FROM emojis ORDER BY createTime DESC")
    suspend fun getAllEmojisOnce(): List<EmojiEntity>

    /** 全部「表情 × 标签」挂载行：导出时要把标签名挂到每条记录上。 */
    @Query("SELECT * FROM emoji_tags")
    suspend fun getAllTagLinks(): List<EmojiTagCrossRef>

    @Query("DELETE FROM emoji_tags")
    suspend fun clearTagLinks()

    @Query("DELETE FROM emojis")
    suspend fun clearEmojis()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    /**
     * 清空整个表情库（REPLACE 导入在插入新数据之前调）。
     *
     * 必须是事务：中途失败留下「表情没了、标签还挂着」这种半截状态，
     * 界面上的标签筛选会指向一堆空标签。
     */
    @Transaction
    suspend fun clearLibrary() {
        clearTagLinks()
        clearFeatures()
        clearEmojis()
        clearTags()
    }

    // ---------- 内容指纹（重复检索用） ----------

    /** 全库指纹一次读出来：判定重复时先拿它当缓存，命中就不必再读文件。 */
    @Query("SELECT * FROM image_features")
    suspend fun getAllFeatures(): List<ImageFeatureEntity>

    /** 这一次算出来的指纹写回去；同一张图再次算过就覆盖旧值。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeatures(features: List<ImageFeatureEntity>)

    /** 表情记录被删时外键会级联清掉指纹，这里只是换库（REPLACE 导入）前顺手清干净。 */
    @Query("DELETE FROM image_features")
    suspend fun clearFeatures()
}
