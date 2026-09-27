package com.aris.emojichan.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class EmojiRepository(context: Context) {
    private val emojiDao = EmojiDatabase.getDatabase(context).emojiDao()

    fun getAllEmojis(): Flow<List<EmojiEntity>> = emojiDao.getAllEmojis()

    fun getFavorites(): Flow<List<EmojiEntity>> = emojiDao.getFavorites()

    fun getByCategory(category: String): Flow<List<EmojiEntity>> =
        emojiDao.getByCategory(category)

    fun search(query: String): Flow<List<EmojiEntity>> = emojiDao.search(escapeLike(query))

    fun searchFavorites(query: String): Flow<List<EmojiEntity>> =
        emojiDao.searchFavorites(escapeLike(query))

    fun searchInCategory(query: String, category: String): Flow<List<EmojiEntity>> =
        emojiDao.searchInCategory(escapeLike(query), category)

    /**
     * 把用户输入里的 LIKE 通配符转义掉（emc-1-035）。
     *
     * 参数一直是绑定的、没有注入问题；问题是「%」和「_」在 LIKE 里是通配符 ——
     * 搜一个「%」会把全部记录都捞出来，看起来像搜索没生效。反斜杠要先转，
     * 否则会把后面补的转义符再转一遍。
     */
    private fun escapeLike(query: String): String = query
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    fun observeById(id: Long): Flow<EmojiEntity?> = emojiDao.observeById(id)

    suspend fun getById(id: Long): EmojiEntity? = emojiDao.getById(id)

    fun getAllCategories(): Flow<List<String>> = emojiDao.getAllCategories()

    fun getCount(): Flow<Int> = emojiDao.getCount()

    /** 最近发送过的表情，按使用时间倒序，最多 [limit] 条。 */
    fun getRecentEmojis(limit: Int): Flow<List<EmojiEntity>> = emojiDao.getRecentEmojis(limit)

    suspend fun insert(emoji: EmojiEntity): Long = emojiDao.insert(emoji)

    suspend fun insertAll(emojis: List<EmojiEntity>) = emojiDao.insertAll(emojis)

    suspend fun delete(emoji: EmojiEntity) = emojiDao.delete(emoji)

    suspend fun deleteByIds(ids: List<Long>) = emojiDao.deleteByIds(ids)

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

    // ---------- 分类管理 ----------

    /** @return false 表示同名分类已存在。 */
    suspend fun insertCategory(name: String): Boolean =
        emojiDao.insertCategory(CategoryEntity(name = name)) != -1L

    /** @return false 表示目标名已被占用，或原分类已不存在。 */
    suspend fun renameCategory(oldName: String, newName: String): Boolean =
        emojiDao.renameCategoryEverywhere(oldName, newName)

    /** @return false 表示该分类不存在。分类下的表情改挂到 [fallbackCategory]，不会被删除。 */
    suspend fun deleteCategory(name: String, fallbackCategory: String): Boolean =
        emojiDao.deleteCategoryByName(name, fallbackCategory) > 0
}
