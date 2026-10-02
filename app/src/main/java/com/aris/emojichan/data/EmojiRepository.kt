package com.aris.emojichan.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class EmojiRepository(context: Context) {
    private val emojiDao = EmojiDatabase.getDatabase(context).emojiDao()

    fun getAllEmojis(): Flow<List<EmojiEntity>> = emojiDao.getAllEmojis()

    fun getFavorites(): Flow<List<EmojiEntity>> = emojiDao.getFavorites()

    /** 统一过滤入口：收藏 / 关键词（普通、`$TAG:`）/ 标签（AND、OR）。 */
    fun observeFiltered(filter: EmojiFilter): Flow<List<EmojiEntity>> =
        emojiDao.observeFiltered(EmojiQuery.build(filter))

    /** 过滤查询的一次性版本（suspend：Room 不允许在主线程上跑一次性查询）。 */
    suspend fun findFiltered(filter: EmojiFilter): List<EmojiEntity> =
        emojiDao.findFiltered(EmojiQuery.build(filter))

    fun observeById(id: Long): Flow<EmojiEntity?> = emojiDao.observeById(id)

    suspend fun getById(id: Long): EmojiEntity? = emojiDao.getById(id)

    fun getCount(): Flow<Int> = emojiDao.getCount()

    /** 最近发送过的表情，按使用时间倒序，最多 [limit] 条。 */
    fun getRecentEmojis(limit: Int): Flow<List<EmojiEntity>> = emojiDao.getRecentEmojis(limit)

    // ---------- 导入 ----------

    /**
     * 导入入库：名字保持原样、重名加 (1)(2)…，并按文件类型自动挂上「图片」/「动图」标签。
     * @return 新行的 id。
     */
    suspend fun insert(emoji: EmojiEntity): Long =
        emojiDao.importEmoji(emoji, autoTagFor(emoji.fileType))

    /**
     * 批量导入（文件夹导入用）。
     * @param tagIds 非空时给这一批表情全部挂上这些标签（文件夹导入会带上路上每一层的名字）。
     * @return 新行的 id 列表（顺序与入参一致，失败的位置是 -1）。
     */
    suspend fun insertAll(emojis: List<EmojiEntity>, tagIds: List<Long> = emptyList()): List<Long> {
        val ids = emojis.map { emoji -> emojiDao.importEmoji(emoji, autoTagFor(emoji.fileType)) }
        if (tagIds.isNotEmpty()) {
            val ok = ids.filter { it > 0 }
            if (ok.isNotEmpty()) emojiDao.addTagsTo(ok, tagIds)
        }
        return ids
    }

    /** 动图挂「动图」，其余一律挂「图片」。 */
    private fun autoTagFor(fileType: String): String =
        if (fileType.equals("gif", ignoreCase = true)) EmojiDefaults.TAG_ANIMATED
        else EmojiDefaults.TAG_IMAGE

    suspend fun delete(emoji: EmojiEntity) = emojiDao.deleteEmojisByIds(listOf(emoji.id))

    suspend fun deleteByIds(ids: List<Long>) = emojiDao.deleteEmojisByIds(ids)

    suspend fun getByIds(ids: List<Long>): List<EmojiEntity> = emojiDao.getByIds(ids)

    suspend fun getFilePathsByIds(ids: List<Long>): List<String> =
        emojiDao.getFilePathsByIds(ids)

    suspend fun getAllFilePaths(): List<String> = emojiDao.getAllFilePaths()

    /** @return 实际更新的行数，0 表示该 id 已不存在。 */
    suspend fun rename(id: Long, newName: String): Int = emojiDao.rename(id, newName)

    suspend fun update(emoji: EmojiEntity) = emojiDao.update(emoji)

    /** @return 实际更新的行数，0 表示该 id 已不存在。 */
    suspend fun updateFavorite(id: Long, isFavorite: Boolean): Int =
        emojiDao.updateFavorite(id, isFavorite)

    /** 记录一次发送：使用次数 +1、lastUsedTime 刷新为 [time]。 */
    suspend fun recordUsage(id: Long, time: Long = System.currentTimeMillis()): Int =
        emojiDao.recordUsage(id, time)

    // ---------- 标签管理 ----------

    fun observeTags(): Flow<List<TagEntity>> = emojiDao.observeTags()

    suspend fun getTags(): List<TagEntity> = emojiDao.getTags()

    /** @return false 表示同名标签已存在。 */
    suspend fun insertTag(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return emojiDao.insertTag(TagEntity(name = trimmed)) != -1L
    }

    /**
     * 新建标签，名字与已有标签撞车时按 [EmojiNaming.unique] 加 (1)(2)…（文件夹导入用：
     * 每次导入都留下自己那个标签，不与历史标签混在一起）。
     * @return 新标签的 id；名字为空返回 null。
     */
    suspend fun createUniqueTag(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val unique = EmojiNaming.unique(trimmed, emojiDao.getAllTagNames())
        return emojiDao.insertTag(TagEntity(name = unique)).takeIf { it != -1L }
    }

    /** 标签不存在就建一个（自动标签「图片」「动图」用，同名直接复用）。@return 标签 id。 */
    suspend fun ensureTag(name: String): Long? = emojiDao.ensureTag(name)

    /** 重命名标签。@return false 表示标签不存在，或新名字已被别的标签占用。 */
    suspend fun renameTag(tagId: Long, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val existing = emojiDao.getTagByName(trimmed)
        if (existing != null && existing.id != tagId) return false
        return emojiDao.renameTag(tagId, trimmed) > 0
    }

    /** @return false 表示标签不存在。标签下所有表情的挂载一起摘掉。 */
    suspend fun deleteTag(tagId: Long): Boolean = emojiDao.deleteTagEverywhere(tagId)

    fun observeTagsOf(emojiId: Long): Flow<List<TagEntity>> = emojiDao.observeTagsOf(emojiId)

    suspend fun getTagsOf(emojiId: Long): List<TagEntity> = emojiDao.getTagsOf(emojiId)

    /** 批量打标签。@return 新挂上的条数。 */
    suspend fun addTags(emojiIds: List<Long>, tagIds: List<Long>): Int =
        emojiDao.addTagsTo(emojiIds, tagIds)

    /** 批量摘标签。@return 实际删掉的条数。 */
    suspend fun removeTags(emojiIds: List<Long>, tagIds: List<Long>): Int =
        emojiDao.removeTagsFrom(emojiIds, tagIds)

    /** 把一张表情的标签集合整体换掉（详情页编辑）。 */
    suspend fun setTagsOf(emojiId: Long, tagIds: List<Long>) =
        emojiDao.setTagsOf(emojiId, tagIds)

    // ---------- 表情库打包导出 / 导入 ----------

    /**
     * 全库快照（导出备份包、MERGE 导入时算去重用）。
     *
     * 刻意不复用 [getAllEmojis]：那条是 Flow，打包是一次性的活，
     * 收 Flow 还得自己 first() 加超时，不如直查一次干净。
     */
    suspend fun getAllOnce(): List<EmojiEntity> = emojiDao.getAllEmojisOnce()

    /** 全部「表情 × 标签」挂载行（导出时把标签名写进清单）。 */
    suspend fun getAllTagLinksOnce(): List<EmojiTagCrossRef> = emojiDao.getAllTagLinks()

    /** 清空表情库（REPLACE 导入换库用）。表情文件由调用方负责删。 */
    suspend fun clearLibrary() = emojiDao.clearLibrary()

    // ---------- 存储管理 ----------

    // ---------- 内容指纹（重复检索用） ----------

    /** 全库指纹。判定「完全相同」时先拿它当缓存，体积和修改时间都对得上就复用。 */
    suspend fun getAllFeatures(): List<ImageFeatureEntity> = emojiDao.getAllFeatures()

    /** 写入这次新算出来的指纹。空列表直接跳过，省一次空事务。 */
    suspend fun saveFeatures(features: List<ImageFeatureEntity>) {
        if (features.isNotEmpty()) emojiDao.upsertFeatures(features)
    }

    /** 压缩替换：文件换成了新的那份，路径、体积、尺寸跟着改。 */
    suspend fun updateCompressed(
        id: Long,
        filePath: String,
        fileType: String,
        fileSize: Long,
        width: Int,
        height: Int
    ) = emojiDao.updateCompressed(id, filePath, fileType, fileSize, width, height)
}
