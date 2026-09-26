package com.aris.emojichan.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class EmojiRepository(context: Context) {
    private val emojiDao = EmojiDatabase.getDatabase(context).emojiDao()

    fun getAllEmojis(): Flow<List<EmojiEntity>> = emojiDao.getAllEmojis()

    fun getFavorites(): Flow<List<EmojiEntity>> = emojiDao.getFavorites()

    fun getByCategory(category: String): Flow<List<EmojiEntity>> =
        emojiDao.getByCategory(category)

    fun search(query: String): Flow<List<EmojiEntity>> = emojiDao.search(query)

    fun searchFavorites(query: String): Flow<List<EmojiEntity>> =
        emojiDao.searchFavorites(query)

    fun searchInCategory(query: String, category: String): Flow<List<EmojiEntity>> =
        emojiDao.searchInCategory(query, category)

    fun observeById(id: Long): Flow<EmojiEntity?> = emojiDao.observeById(id)

    suspend fun getById(id: Long): EmojiEntity? = emojiDao.getById(id)

    fun getAllCategories(): Flow<List<String>> = emojiDao.getAllCategories()

    fun getCount(): Flow<Int> = emojiDao.getCount()

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
