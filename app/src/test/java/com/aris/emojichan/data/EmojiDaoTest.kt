package com.aris.emojichan.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据层回归测试。
 *
 * 重点守住两条曾经出过事的规则：
 * 1. 写操作只改自己那一列，不能把整行其它字段覆盖回默认值；
 * 2. 分类表与表情表在重命名 / 删除分类时必须一起动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmojiDaoTest {

    private lateinit var db: EmojiDatabase
    private lateinit var dao: EmojiDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EmojiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.emojiDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun emoji(
        name: String,
        category: String = "默认",
        tags: String = "",
        source: String = "local",
        isFavorite: Boolean = false,
        usageCount: Int = 0,
        lastUsedTime: Long = 0L
    ) = EmojiEntity(
        name = name,
        filePath = "/data/emojis/$name.png",
        fileType = "image",
        category = category,
        tags = tags,
        source = source,
        isFavorite = isFavorite,
        usageCount = usageCount,
        lastUsedTime = lastUsedTime
    )

    @Test
    fun insertThenQueryAll() = runTest {
        val id = dao.insert(emoji("a"))
        assertTrue(id > 0)

        val all = dao.getAllEmojis().first()
        assertEquals(1, all.size)
        assertEquals("a", all[0].name)
    }

    /** 回归：详情页点收藏只应写 isFavorite 一列。 */
    @Test
    fun updateFavoriteKeepsOtherColumns() = runTest {
        val id = dao.insert(
            emoji("a", tags = "测试", source = "wechat", usageCount = 5, lastUsedTime = 123L)
        )

        assertEquals(1, dao.updateFavorite(id, true))

        val after = dao.getById(id)!!
        assertTrue(after.isFavorite)
        assertEquals("测试", after.tags)
        assertEquals("wechat", after.source)
        assertEquals(5, after.usageCount)
        assertEquals(123L, after.lastUsedTime)
    }

    /** 回归：重命名只应写 name 一列。 */
    @Test
    fun renameKeepsOtherColumns() = runTest {
        val id = dao.insert(emoji("a", tags = "测试", usageCount = 3))

        assertEquals(1, dao.rename(id, "b"))

        val after = dao.getById(id)!!
        assertEquals("b", after.name)
        assertEquals("测试", after.tags)
        assertEquals(3, after.usageCount)
    }

    @Test
    fun writeOnMissingRowReturnsZero() = runTest {
        assertEquals(0, dao.rename(999L, "x"))
        assertEquals(0, dao.updateFavorite(999L, true))
    }

    @Test
    fun searchMatchesNameTagsAndSource() = runTest {
        dao.insert(emoji("cat"))
        dao.insert(emoji("dog", tags = "cat-tag"))
        dao.insert(emoji("bird", source = "cat-source"))
        dao.insert(emoji("fish"))

        assertEquals(3, dao.search("cat").first().size)
        assertEquals(1, dao.search("fish").first().size)
    }

    @Test
    fun favoritesAndCategoryFilters() = runTest {
        // 查询词不要用单字母：source 默认值是 "local"，含 a / l / o / c，
        // 搜这些字母会把所有本地表情一并命中。
        dao.insert(emoji("red", category = "组1", isFavorite = true))
        dao.insert(emoji("blue", category = "组1"))
        dao.insert(emoji("green", category = "组2", isFavorite = true))

        assertEquals(2, dao.getFavorites().first().size)
        assertEquals(2, dao.getByCategory("组1").first().size)
        assertEquals(3, dao.getCount().first())
        assertEquals(1, dao.searchInCategory("red", "组1").first().size)
    }

    @Test
    fun deleteByIdsRemovesOnlyListedRows() = runTest {
        val a = dao.insert(emoji("a"))
        val b = dao.insert(emoji("b"))

        dao.deleteByIds(listOf(a))

        val all = dao.getAllEmojis().first()
        assertEquals(1, all.size)
        assertEquals(b, all[0].id)
    }

    @Test
    fun getByIdsAndFilePaths() = runTest {
        val a = dao.insert(emoji("a"))
        val b = dao.insert(emoji("b"))

        assertEquals(setOf(a, b), dao.getByIds(listOf(a, b)).map { it.id }.toSet())
        assertEquals(2, dao.getAllFilePaths().size)
        assertEquals(listOf("/data/emojis/a.png"), dao.getFilePathsByIds(listOf(a)))
    }

    /** 分类列表要同时覆盖分类表与历史数据里的分类名。 */
    @Test
    fun getAllCategoriesUnionsBothTables() = runTest {
        dao.insert(emoji("a", category = "历史分类"))
        dao.insertCategory(CategoryEntity(name = "新分类"))

        val categories = dao.getAllCategories().first()
        assertEquals(setOf("历史分类", "新分类"), categories.toSet())
    }

    @Test
    fun duplicateCategoryIsRejected() = runTest {
        assertTrue(dao.insertCategory(CategoryEntity(name = "组")) > 0)
        assertEquals(-1L, dao.insertCategory(CategoryEntity(name = "组")))
        assertNotNull(dao.getCategoryByName("组"))
    }

    @Test
    fun renameCategoryUpdatesBothTables() = runTest {
        dao.insertCategory(CategoryEntity(name = "旧"))
        val id = dao.insert(emoji("a", category = "旧"))

        assertTrue(dao.renameCategoryEverywhere("旧", "新"))

        assertEquals("新", dao.getById(id)!!.category)
        assertNotNull(dao.getCategoryByName("新"))
        assertNull(dao.getCategoryByName("旧"))
    }

    @Test
    fun renameCategoryRejectsTakenName() = runTest {
        dao.insertCategory(CategoryEntity(name = "甲"))
        dao.insertCategory(CategoryEntity(name = "乙"))
        val id = dao.insert(emoji("a", category = "甲"))

        assertFalse(dao.renameCategoryEverywhere("甲", "乙"))
        assertEquals("甲", dao.getById(id)!!.category)
    }

    @Test
    fun renameMissingCategoryReturnsFalse() = runTest {
        assertFalse(dao.renameCategoryEverywhere("不存在", "新"))
    }

    /** 删除分类不能把分类下的表情一起删掉，只把它们改挂到兜底分类。 */
    @Test
    fun deleteCategoryMovesEmojisToFallback() = runTest {
        dao.insertCategory(CategoryEntity(name = "临时"))
        val id = dao.insert(emoji("a", category = "临时"))

        assertEquals(1, dao.deleteCategoryByName("临时", "默认"))

        assertNotNull(dao.getById(id))
        assertEquals("默认", dao.getById(id)!!.category)
        assertNull(dao.getCategoryByName("临时"))
    }

    @Test
    fun deleteMissingCategoryReturnsZero() = runTest {
        assertEquals(0, dao.deleteCategoryByName("不存在", "默认"))
    }
}
