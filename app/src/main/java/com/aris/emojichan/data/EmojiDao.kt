package com.aris.emojichan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    // 只按名称与标签匹配：source 目前恒为 "local"，参与 LIKE 会让搜 a/l/o/c 命中全部记录。
    @Query("SELECT * FROM emojis WHERE name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY createTime DESC")
    fun search(query: String): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE isFavorite = 1 AND (name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY createTime DESC")
    fun searchFavorites(query: String): Flow<List<EmojiEntity>>

    @Query("SELECT * FROM emojis WHERE category = :category AND (name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY createTime DESC")
    fun searchInCategory(query: String, category: String): Flow<List<EmojiEntity>>

    /**
     * 分类列表 = 分类表登记的分类 ∪ 表情表里实际出现过的分类。
     *
     * 用 UNION 而不是只查分类表：历史数据的分类只存在于表情表里，
     * 只查分类表会让它们集体消失。UNION 自身会去重。
     */
    @Query("SELECT name FROM categories UNION SELECT DISTINCT category FROM emojis ORDER BY name")
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

    /** 数据库当前引用的全部文件路径，用于清理孤儿文件。 */
    @Query("SELECT filePath FROM emojis")
    suspend fun getAllFilePaths(): List<String>

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

    /**
     * 最近用过的表情，供发送面板的「最近」分组使用。
     *
     * 过滤 lastUsedTime > 0 而不是按 usageCount：lastUsedTime 为 0 表示从未发送过，
     * 这类记录按时间倒序排会全部堆在最前面（默认值 0 最小，反序后反而最靠后，
     * 但语义上它们不该出现在「最近」里）。
     */
    @Query("SELECT * FROM emojis WHERE lastUsedTime > 0 ORDER BY lastUsedTime DESC LIMIT :limit")
    fun getRecentEmojis(limit: Int): Flow<List<EmojiEntity>>

    /** 记录一次发送：次数 +1、时间戳刷新。@return 实际更新的行数，0 表示该 id 已不存在。 */
    @Query("UPDATE emojis SET usageCount = usageCount + 1, lastUsedTime = :time WHERE id = :id")
    suspend fun recordUsage(id: Long, time: Long): Int

    // ---------- 分类管理 ----------

    /** @return 新行的 id；返回 -1 表示同名分类已存在（被 UNIQUE 索引挡下）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT * FROM categories WHERE name = :name")
    suspend fun getCategoryByName(name: String): CategoryEntity?

    @Query("UPDATE categories SET name = :newName WHERE id = :id")
    suspend fun renameCategoryRow(id: Long, newName: String): Int

    /** 把表情表里挂在该分类下的记录一并改名。 */
    @Query("UPDATE emojis SET category = :newName WHERE category = :oldName")
    suspend fun renameCategoryInEmojis(oldName: String, newName: String): Int

    /** 把某分类下的表情整体改挂到另一个分类（删除分类时用）。 */
    @Query("UPDATE emojis SET category = :target WHERE category = :source")
    suspend fun moveEmojisToCategory(source: String, target: String): Int

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategoryRow(id: Long): Int

    @Query("SELECT COUNT(*) FROM emojis WHERE category = :category")
    suspend fun countInCategory(category: String): Int

    /**
     * 重命名分类：分类表与表情表必须一起改，所以包在同一个事务里。
     * @return false 表示目标名已被占用，或原分类已不存在。
     */
    @Transaction
    suspend fun renameCategoryEverywhere(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        if (getCategoryByName(newName) != null) return false
        val category = getCategoryByName(oldName) ?: return false
        renameCategoryRow(category.id, newName)
        renameCategoryInEmojis(oldName, newName)
        return true
    }

    /**
     * 删除分类：该分类下的表情改挂到 [fallbackCategory]，**不跟着一起删**。
     * 两个动作同一个事务，避免出现「分类没了、表情还挂在一个不存在的分类下」。
     * @return 删除的分类行数，0 表示该分类不存在。
     */
    @Transaction
    suspend fun deleteCategoryByName(name: String, fallbackCategory: String): Int {
        val category = getCategoryByName(name) ?: return 0
        moveEmojisToCategory(name, fallbackCategory)
        return deleteCategoryRow(category.id)
    }
}
