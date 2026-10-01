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
 * 重点守住三条曾经出过事、或者结构一变就容易再出事的规则：
 * 1. 写操作只改自己那一列，不能把整行其它字段覆盖回默认值；
 * 2. 标签走 emoji_tags 真表，过滤的 AND / OR 语义必须是数据库算出来的；
 * 3. 导入时必须去重命名并自动挂「图片 / 动图」标签。
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
        fileType: String = "image",
        source: String = "local",
        isFavorite: Boolean = false,
        usageCount: Int = 0,
        lastUsedTime: Long = 0L,
        createTime: Long = 0L
    ) = EmojiEntity(
        name = name,
        filePath = "/data/emojis/$name.png",
        fileType = fileType,
        source = source,
        isFavorite = isFavorite,
        usageCount = usageCount,
        lastUsedTime = lastUsedTime,
        createTime = createTime
    )

    /** 走一遍过滤查询（UI 上的包 / 收藏 / 搜索 / 标签筛选最终都落到这里）。 */
    private suspend fun find(filter: EmojiFilter): List<EmojiEntity> =
        dao.findFiltered(EmojiQuery.build(filter))

    private suspend fun tagIdsOf(vararg names: String): List<Long> =
        names.map { name -> dao.ensureTag(name) ?: error("标签 $name 没建起来") }

    // ---------- 基础读写 ----------

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
        val id = dao.importEmoji(
            emoji("a", source = "wechat", usageCount = 5, lastUsedTime = 123L),
            EmojiDefaults.TAG_IMAGE
        )

        assertEquals(1, dao.updateFavorite(id, true))

        val after = dao.getById(id)!!
        assertTrue(after.isFavorite)
        assertEquals("wechat", after.source)
        assertEquals(5, after.usageCount)
        assertEquals(123L, after.lastUsedTime)
        assertEquals(listOf(EmojiDefaults.TAG_IMAGE), dao.getTagsOf(id).map { it.name })
    }

    /** 回归：重命名只应写 name 一列。 */
    @Test
    fun renameKeepsOtherColumns() = runTest {
        val id = dao.importEmoji(emoji("a", usageCount = 3), EmojiDefaults.TAG_IMAGE)

        assertEquals(1, dao.rename(id, "b"))

        val after = dao.getById(id)!!
        assertEquals("b", after.name)
        assertEquals(3, after.usageCount)
        assertEquals(listOf(EmojiDefaults.TAG_IMAGE), dao.getTagsOf(id).map { it.name })
    }

    @Test
    fun writeOnMissingRowReturnsZero() = runTest {
        assertEquals(0, dao.rename(999L, "x"))
        assertEquals(0, dao.updateFavorite(999L, true))
    }

    // ---------- 导入：去重命名 + 自动标签 ----------

    /** 需求：导入后表情名不变，同名时后一个加 (1)(2)… */
    @Test
    fun importDedupesNames() = runTest {
        dao.importEmoji(emoji("猫猫"), EmojiDefaults.TAG_IMAGE)
        dao.importEmoji(emoji("猫猫"), EmojiDefaults.TAG_IMAGE)
        dao.importEmoji(emoji("猫猫"), EmojiDefaults.TAG_IMAGE)

        assertEquals(
            listOf("猫猫", "猫猫(1)", "猫猫(2)"),
            dao.getAllNames().sorted()
        )
        assertEquals(3, dao.getAllEmojis().first().size)
    }

    /** 需求：导入后自动挂「图片」/「动图」标签。 */
    @Test
    fun importAttachesAutoTag() = runTest {
        val still = dao.importEmoji(emoji("still", fileType = "image"), EmojiDefaults.TAG_IMAGE)
        val moving = dao.importEmoji(emoji("moving", fileType = "gif"), EmojiDefaults.TAG_ANIMATED)

        assertEquals(listOf(EmojiDefaults.TAG_IMAGE), dao.getTagsOf(still).map { it.name })
        assertEquals(listOf(EmojiDefaults.TAG_ANIMATED), dao.getTagsOf(moving).map { it.name })
        // 两个标签都建在 tags 表里，第二个导入不会把第一个的标签一并挂上
        assertEquals(2, dao.getTags().size)
    }

    @Test
    fun ensureTagIsIdempotent() = runTest {
        val first = dao.ensureTag("可爱")
        val second = dao.ensureTag("可爱")
        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(1, dao.getTags().size)
    }

    // ---------- 搜索与过滤 ----------

    @Test
    fun searchMatchesNameAndTagsOnly() = runTest {
        dao.insert(emoji("cat"))
        dao.importEmoji(emoji("dog"), "cat-tag")
        dao.insert(emoji("bird", source = "cat-source"))
        dao.insert(emoji("fish"))

        // bird 只有 source 含 cat，不该被搜出来
        assertEquals(2, find(EmojiFilter(keyword = "cat")).size)
        assertEquals(1, find(EmojiFilter(keyword = "fish")).size)
    }

    @Test
    fun searchIgnoresSourceColumn() = runTest {
        // 名称里不能出现 a —— cat 本身就含 a，会让这个断言失去意义。
        dao.insert(emoji("dog"))
        dao.insert(emoji("bird"))
        dao.insert(emoji("fish"))

        // 三条记录的 source 都是默认的 "local"（含 a/l/o/c）。
        // 若 source 参与 LIKE，这两个查询会把三条全部命中。
        assertEquals(0, find(EmojiFilter(keyword = "a")).size)
        assertEquals(0, find(EmojiFilter(keyword = "local")).size)
    }

    /** 收藏与关键词可以叠加（分类没了，筛选只剩这两个维度加标签）。 */
    @Test
    fun favoritesAndKeywordCombine() = runTest {
        // 单字母查询词曾是坑：source 恒为 "local" 且参与 LIKE，搜 a/l/o/c 会命中全部记录。
        dao.insert(emoji("red", isFavorite = true))
        dao.insert(emoji("blue"))
        dao.insert(emoji("green", isFavorite = true))

        assertEquals(2, dao.getFavorites().first().size)
        assertEquals(3, dao.getCount().first())
        assertEquals(1, find(EmojiFilter(keyword = "red")).size)
        assertEquals(1, find(EmojiFilter(favoritesOnly = true, keyword = "green")).size)
        // 收藏 + 关键词互斥的组合要真的查空，而不是退化成只看收藏
        assertEquals(0, find(EmojiFilter(favoritesOnly = true, keyword = "blue")).size)
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

    // ---------- 标签过滤（AND / OR） ----------

    @Test
    fun tagFilterMatchesAllOrAny() = runTest {
        val both = dao.importEmoji(emoji("both"), null)
        val onlyCat = dao.importEmoji(emoji("onlyCat"), null)
        val onlyCute = dao.importEmoji(emoji("onlyCute"), null)
        dao.importEmoji(emoji("none"), null)

        val cat = dao.ensureTag("猫")!!
        val cute = dao.ensureTag("可爱")!!
        dao.addTagsTo(listOf(both), listOf(cat, cute))
        dao.addTagsTo(listOf(onlyCat), listOf(cat))
        dao.addTagsTo(listOf(onlyCute), listOf(cute))

        val all = find(EmojiFilter(tagIds = listOf(cat, cute), tagMatchAll = true))
        assertEquals(listOf(both), all.map { it.id })

        val any = find(EmojiFilter(tagIds = listOf(cat, cute), tagMatchAll = false))
        assertEquals(setOf(both, onlyCat, onlyCute), any.map { it.id }.toSet())
    }

    /** 保留 v0.1.305 的旧行为：普通关键词也要能命中标签名。 */
    @Test
    fun nameSearchMatchesTagNames() = runTest {
        dao.importEmoji(emoji("dog"), "cat-tag")
        dao.insert(emoji("fish"))

        assertEquals(1, find(EmojiFilter(keyword = "cat-tag")).size)
    }

    /** $TAG: 是「按标签找」，此时再叠标签 chip 同样只会撞出空结果。 */
    @Test
    fun tagPrefixIgnoresTagChips() = runTest {
        val a = dao.importEmoji(emoji("a"), "猫")
        val b = dao.importEmoji(emoji("b"), "猫")
        dao.importEmoji(emoji("c"), "狗")

        val (mode, keyword) = EmojiFilter.parseQuery("\$TAG:猫")
        assertEquals(SearchMode.TAG, mode)
        val ids = find(
            EmojiFilter(
                keyword = keyword,
                searchMode = mode,
                tagIds = listOf(dao.ensureTag("狗")!!),
                tagMatchAll = true
            )
        ).map { it.id }.toSet()
        assertEquals(setOf(a, b), ids)
    }

    @Test
    fun parseQueryCases() = runTest {
        assertEquals(SearchMode.TAG to "猫", EmojiFilter.parseQuery("\$tag: 猫 "))
        // 前缀后面还没输关键词时退回普通搜索，否则列表会突然空掉
        assertEquals(SearchMode.NAME to "", EmojiFilter.parseQuery("\$TAG:"))
        assertEquals(SearchMode.NAME to "猫猫", EmojiFilter.parseQuery("猫猫"))
        // 前缀大小写不敏感
        assertEquals(SearchMode.TAG to "猫", EmojiFilter.parseQuery("\$Tag:猫"))
    }

    /** LIKE 的通配符必须转义，否则搜「%」会把全部表情捞出来。 */
    @Test
    fun wildcardKeywordDoesNotMatchEverything() = runTest {
        dao.insert(emoji("cat"))
        dao.insert(emoji("dog"))

        assertEquals(0, find(EmojiFilter(keyword = "%")).size)
        assertEquals(0, find(EmojiFilter(keyword = "_")).size)
        // 下划线被转义成字面量，所以 "c_t" 只匹配名字里真的有下划线的记录（这里没有）
        assertEquals(0, find(EmojiFilter(keyword = "c_t")).size)
    }

    @Test
    fun escapeLikeEscapesWildcards() = runTest {
        assertEquals("50\\%\\_a\\\\b", EmojiQuery.escapeLike("50%_a\\b"))
    }

    // ---------- 标签写操作 ----------

    @Test
    fun setTagsOfReplacesWholeSet() = runTest {
        val id = dao.importEmoji(emoji("a"), EmojiDefaults.TAG_IMAGE)
        val cute = dao.ensureTag("可爱")!!

        dao.setTagsOf(id, listOf(cute))

        assertEquals(listOf("可爱"), dao.getTagsOf(id).map { it.name })
        // 标签本身不能因为摘光了表情就消失
        assertNotNull(dao.getTagByName(EmojiDefaults.TAG_IMAGE))
    }

    @Test
    fun removeTagsFromDetachesOnlySomeLinks() = runTest {
        val id = dao.importEmoji(emoji("a"), null)
        val cat = dao.ensureTag("猫")!!
        val cute = dao.ensureTag("可爱")!!
        dao.addTagsTo(listOf(id), listOf(cat, cute))

        assertEquals(1, dao.removeTagsFrom(listOf(id), listOf(cat)))

        assertEquals(listOf("可爱"), dao.getTagsOf(id).map { it.name })
    }

    @Test
    fun deleteTagEverywhereRemovesLinks() = runTest {
        val id = dao.importEmoji(emoji("a"), "猫")
        val cat = dao.getTagByName("猫")!!

        assertTrue(dao.deleteTagEverywhere(cat.id))

        assertNull(dao.getTagByName("猫"))
        assertTrue(dao.getTagsOf(id).isEmpty())
        // 表情本身不受影响
        assertNotNull(dao.getById(id))
    }

    @Test
    fun deleteEmojiRemovesItsLinks() = runTest {
        val id = dao.importEmoji(emoji("a"), "猫")

        dao.deleteEmojisByIds(listOf(id))

        assertNull(dao.getById(id))
        assertTrue(dao.getTagsOf(id).isEmpty())
        assertNotNull(dao.getTagByName("猫"))
    }

    // ---------- 标签管理（重命名 / 唯一性） ----------

    /** 重命名只动 tags 一行，挂载关系与表情都不受影响。 */
    @Test
    fun renameTagKeepsLinks() = runTest {
        val id = dao.importEmoji(emoji("a"), "旧名")
        val tag = dao.getTagByName("旧名")!!

        assertEquals(1, dao.renameTag(tag.id, "新名"))

        assertNull(dao.getTagByName("旧名"))
        assertNotNull(dao.getTagByName("新名"))
        assertEquals(listOf("新名"), dao.getTagsOf(id).map { it.name })
        assertNotNull(dao.getById(id))
    }

    @Test
    fun renameMissingTagReturnsZero() = runTest {
        assertEquals(0, dao.renameTag(999L, "新名"))
    }

    @Test
    fun duplicateTagNameIsRejected() = runTest {
        assertTrue(dao.insertTag(TagEntity(name = "组")) > 0)
        assertEquals(-1L, dao.insertTag(TagEntity(name = "组")))
        assertNotNull(dao.getTagByName("组"))
    }

    /** 建标签前要先拿全量名字去重（[EmojiNaming.unique] 的输入就是它）。 */
    @Test
    fun allTagNamesFeedTheUniqueNamer() = runTest {
        dao.insertTag(TagEntity(name = "表情"))
        dao.insertTag(TagEntity(name = "表情(1)"))

        assertEquals(listOf("表情", "表情(1)"), dao.getAllTagNames().sorted())
        assertEquals("表情(2)", EmojiNaming.unique("表情", dao.getAllTagNames()))
    }
}
