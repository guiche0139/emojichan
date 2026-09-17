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

    fun getAllCategories(): Flow<List<String>> = emojiDao.getAllCategories()

    fun getCount(): Flow<Int> = emojiDao.getCount()

    suspend fun insert(emoji: EmojiEntity): Long = emojiDao.insert(emoji)

    suspend fun insertAll(emojis: List<EmojiEntity>) = emojiDao.insertAll(emojis)

    suspend fun delete(emoji: EmojiEntity) = emojiDao.delete(emoji)

    suspend fun deleteByIds(ids: List<Long>) = emojiDao.deleteByIds(ids)

    suspend fun getFilePathsByIds(ids: List<Long>): List<String> =
        emojiDao.getFilePathsByIds(ids)

    suspend fun rename(id: Long, newName: String) = emojiDao.rename(id, newName)

    suspend fun update(emoji: EmojiEntity) = emojiDao.update(emoji)

    suspend fun updateFavorite(id: Long, isFavorite: Boolean) =
        emojiDao.updateFavorite(id, isFavorite)
}
