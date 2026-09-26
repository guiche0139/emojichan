package com.aris.emojichan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmojiDao {
    @Query("SELECT * FROM emojis ORDER BY createTime DESC")
    fun getAllEmojis(): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE isFavorite = 1 ORDER BY createTime DESC")
    fun getFavorites(): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE category = :category ORDER BY createTime DESC")
    fun getByCategory(category: String): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' OR source LIKE '%' || :query || '%' ORDER BY createTime DESC")
    fun search(query: String): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE isFavorite = 1 AND (name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' OR source LIKE '%' || :query || '%') ORDER BY createTime DESC")
    fun searchFavorites(query: String): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE category = :category AND (name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' OR source LIKE '%' || :query || '%') ORDER BY createTime DESC")
    fun searchInCategory(query: String, category: String): Flow<List<EmojiEntity>>

    @Query("SELECT DISTINCT category FROM emojis")
    fun getAllCategories(): Flow<List<String>>

    @Insert
    suspend fun insert(emoji: EmojiEntity): Long

    @Insert
    suspend fun insertAll(emojis: List<EmojiEntity>)

    @Delete
    suspend fun delete(emoji: EmojiEntity)

    @Query("DELETE FROM emojis WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT filePath FROM emojis WHERE id IN (:ids)")
    suspend fun getFilePathsByIds(ids: List<Long>): List<String>

    @Query("SELECT * FROM emojis WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<EmojiEntity>

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
}
