package com.aris.emojichan.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 迁移测试：v2 → v3（「分类」改成「包 + 标签」）、v3 → v4（彻底删掉「包」）
 * 与 v4 → v5（新增内容指纹表）。
 *
 * 不走 Room 的 identity hash 校验：手工用 SupportSQLiteOpenHelper 按 v2 的建表语句
 * 造一个版本号为 2 的库、塞进数据，然后直接调 [EmojiDatabase.MIGRATION_2_3] 的 migrate。
 * 这样测的就是迁移本身，而不是 Room 的版本机件。
 *
 * 迁移必须做到：
 * 1. `emojis.tags` 那一列消失，但所有表情记录（连同其它 13 列的值）都还在；
 * 2. 老的「默认」包改名「未分类」，表情表里的取值一起改，且不撞 UNIQUE 索引；
 * 3. `categories` 多出 lastUsedTime；
 * 4. `tags` / `emoji_tags` 两张表建好（内容为空 —— 老的逗号字符串不迁移成挂载）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmojiDatabaseMigrationTest {

    private lateinit var context: Context
    private val helpers = ArrayList<SupportSQLiteOpenHelper>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        helpers.forEach { helper -> runCatching { helper.close() } }
        helpers.clear()
    }

    // ---------- 造一个 v2 库 ----------

    private val v2Emojis =
        "CREATE TABLE IF NOT EXISTS `emojis` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`filePath` TEXT NOT NULL, " +
            "`fileType` TEXT NOT NULL, " +
            "`category` TEXT NOT NULL, " +
            "`tags` TEXT NOT NULL, " +
            "`source` TEXT NOT NULL, " +
            "`isFavorite` INTEGER NOT NULL, " +
            "`usageCount` INTEGER NOT NULL, " +
            "`lastUsedTime` INTEGER NOT NULL, " +
            "`createTime` INTEGER NOT NULL, " +
            "`fileSize` INTEGER NOT NULL, " +
            "`width` INTEGER NOT NULL, " +
            "`height` INTEGER NOT NULL)"

    private fun openV2(): SupportSQLiteDatabase {
        val name = "migration-" + helpers.size + ".db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v2Emojis)
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_category` ON `emojis` (`category`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_source` ON `emojis` (`source`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_isFavorite` ON `emojis` (`isFavorite`)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `categories` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`name` TEXT NOT NULL, " +
                                "`createTime` INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` " +
                                "ON `categories` (`name`)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        helpers.add(helper)
        return helper.writableDatabase
    }

    private fun insertEmoji(
        db: SupportSQLiteDatabase,
        name: String,
        category: String,
        tags: String,
        usageCount: Int = 1,
        lastUsedTime: Long = 0L
    ) {
        db.execSQL(
            "INSERT INTO `emojis` (`name`, `filePath`, `fileType`, `category`, `tags`, " +
                "`source`, `isFavorite`, `usageCount`, `lastUsedTime`, `createTime`, " +
                "`fileSize`, `width`, `height`) VALUES (" +
                "'$name', '/data/emojis/$name.png', 'image', '$category', '$tags', " +
                "'local', 1, $usageCount, $lastUsedTime, 456, 789, 64, 64)"
        )
    }

    // ---------- 断言辅助 ----------

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val names = ArrayList<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        return names
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getLong(0) > 0
            }
    }

    private fun indexExists(db: SupportSQLiteDatabase, index: String): Boolean {
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index))
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getLong(0) > 0
            }
    }

    private fun strings(db: SupportSQLiteDatabase, sql: String): List<String> {
        val out = ArrayList<String>()
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) out.add(cursor.getString(0))
        }
        return out
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long {
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    // ---------- 测试 ----------

    @Test
    fun migrationDropsTagsColumnAndKeepsEveryRow() {
        val db = openV2()
        insertEmoji(db, "猫", "组1", "旧标签,另一个", usageCount = 7, lastUsedTime = 123L)
        insertEmoji(db, "狗", "组1", "")
        db.execSQL("INSERT INTO `categories` (`name`, `createTime`) VALUES ('组1', 0)")

        EmojiDatabase.MIGRATION_2_3.migrate(db)

        val columns = columnsOf(db, "emojis")
        assertFalse("emojis 不该再有 tags 列：$columns", columns.contains("tags"))
        assertEquals(13, columns.size)
        assertEquals(2, count(db, "emojis"))
        // 其它列的值原样保留
        assertEquals(listOf("猫"), strings(db, "SELECT name FROM emojis WHERE usageCount = 7 AND lastUsedTime = 123 AND isFavorite = 1"))
        assertEquals(listOf("组1"), strings(db, "SELECT DISTINCT category FROM emojis"))
    }

    @Test
    fun migrationRenamesLegacyDefaultCategory() {
        val db = openV2()
        db.execSQL("INSERT INTO `categories` (`name`, `createTime`) VALUES ('默认', 0)")
        db.execSQL("INSERT INTO `categories` (`name`, `createTime`) VALUES ('组1', 0)")
        insertEmoji(db, "猫", "默认", "")
        insertEmoji(db, "狗", "组1", "")

        EmojiDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(listOf("未分类"), strings(db, "SELECT category FROM emojis WHERE name = '猫'"))
        assertEquals(setOf("未分类", "组1"), strings(db, "SELECT name FROM categories").toSet())
        assertTrue(columnsOf(db, "categories").contains("lastUsedTime"))
    }

    /** 已经有人把包叫「未分类」时，老的「默认」那行要删掉，不然撞 UNIQUE 索引。 */
    @Test
    fun migrationMergesLegacyDefaultWhenUnclassifiedExists() {
        val db = openV2()
        db.execSQL("INSERT INTO `categories` (`name`, `createTime`) VALUES ('默认', 0)")
        db.execSQL("INSERT INTO `categories` (`name`, `createTime`) VALUES ('未分类', 0)")

        EmojiDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(listOf("未分类"), strings(db, "SELECT name FROM categories"))
        assertEquals(1, count(db, "categories"))
    }

    /** 标签两张表建好但内容为空：老的逗号字符串不迁移成挂载。 */
    @Test
    fun migrationCreatesEmptyTagTables() {
        val db = openV2()
        insertEmoji(db, "猫", "组1", "旧标签,另一个")

        EmojiDatabase.MIGRATION_2_3.migrate(db)

        assertTrue(tableExists(db, "tags"))
        assertTrue(tableExists(db, "emoji_tags"))
        assertTrue(indexExists(db, "index_tags_name"))
        assertTrue(indexExists(db, "index_emoji_tags_emojiId"))
        assertTrue(indexExists(db, "index_emoji_tags_tagId"))
        // 重建 emojis 时旧索引跟着表一起没了，迁移要把它们补回来
        assertTrue(indexExists(db, "index_emojis_category"))
        assertTrue(indexExists(db, "index_emojis_source"))
        assertTrue(indexExists(db, "index_emojis_isFavorite"))
        assertEquals(0, count(db, "tags"))
        assertEquals(0, count(db, "emoji_tags"))
    }

    // ---------- v3 → v4：彻底删掉「包」 ----------

    /**
     * 造一个 v3 库：先按 v2 建库塞数据，再跑一遍 v2→v3（v3 的表结构就是它造出来的，
     * 手抄 DDL 容易和真实结构对不上），最后塞进标签与挂载。
     */
    private fun openV3WithData(): SupportSQLiteDatabase {
        val db = openV2()
        insertEmoji(db, "猫", "组1", "旧标签")
        insertEmoji(db, "狗", "组2", "")
        EmojiDatabase.MIGRATION_2_3.migrate(db)
        db.execSQL("INSERT INTO `tags` (`name`, `createTime`) VALUES ('可爱', 1)")
        db.execSQL("INSERT INTO `tags` (`name`, `createTime`) VALUES ('图片', 2)")
        db.execSQL("INSERT INTO `emoji_tags` (`emojiId`, `tagId`) VALUES (1, 1)")
        db.execSQL("INSERT INTO `emoji_tags` (`emojiId`, `tagId`) VALUES (2, 2)")
        return db
    }

    /**
     * v3 → v4 的硬要求：category 列真的消失、行一条不少、**标签挂载必须活着**
     * （重建 emojis 时外键的 ON DELETE CASCADE 会顺手清空 emoji_tags，所以迁移
     * 先把挂载抄进一张没有外键的备份表），包那一整套（表、索引）跟着消失。
     */
    @Test
    fun migrationDropsCategoryButKeepsRowsAndTagLinks() {
        val db = openV3WithData()

        EmojiDatabase.MIGRATION_3_4.migrate(db)

        val columns = columnsOf(db, "emojis")
        assertFalse("emojis 不该再有 category 列：$columns", columns.contains("category"))
        assertEquals(12, columns.size)
        assertEquals(2, count(db, "emojis"))
        assertEquals(listOf("猫", "狗"), strings(db, "SELECT name FROM emojis ORDER BY id"))
        // 其它列的值原样保留
        assertEquals(listOf("/data/emojis/猫.png"), strings(db, "SELECT filePath FROM emojis WHERE name = '猫'"))

        // 标签本身与挂载关系都要活下来
        assertEquals(2, count(db, "tags"))
        assertEquals(2, count(db, "emoji_tags"))
        assertEquals(
            listOf("可爱"),
            strings(db, "SELECT t.name FROM tags t JOIN emoji_tags l ON l.tagId = t.id WHERE l.emojiId = 1")
        )
        assertEquals(
            listOf("图片"),
            strings(db, "SELECT t.name FROM tags t JOIN emoji_tags l ON l.tagId = t.id WHERE l.emojiId = 2")
        ) 

        // 包那一整套消失，索引该补的补、该没的没
        assertFalse(tableExists(db, "categories"))
        assertFalse(indexExists(db, "index_emojis_category"))
        assertFalse(tableExists(db, "emoji_tags_backup"))
        assertTrue(indexExists(db, "index_emojis_source"))
        assertTrue(indexExists(db, "index_emojis_isFavorite"))
        assertTrue(indexExists(db, "index_tags_name"))
        assertTrue(indexExists(db, "index_emoji_tags_emojiId"))
        assertTrue(indexExists(db, "index_emoji_tags_tagId"))
    }

    // ---------- v4 → v5：新增内容指纹（重复检索） ----------

    /** 造一个 v4 库：先按 v3 建库塞数据，再跑一遍 v3→v4（v4 的列清单由迁移本身造出来，不手抄）。 */
    private fun openV4WithData(): SupportSQLiteDatabase {
        val db = openV3WithData()
        EmojiDatabase.MIGRATION_3_4.migrate(db)
        return db
    }

    /** 读一段 PRAGMA 的某一列（SupportSQLiteDatabase.query 直接吃 PRAGMA 语句）。 */
    private fun pragma(db: SupportSQLiteDatabase, sql: String, column: String): List<String> {
        val values = mutableListOf<String>()
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                values += cursor.getString(cursor.getColumnIndexOrThrow(column))
            }
        }
        return values
    }

    /** PRAGMA table_info 里主键那一列（pk > 0）的列名，按声明顺序。 */
    private fun primaryKeysOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                if (cursor.getInt(pkIndex) > 0) names += cursor.getString(nameIndex)
            }
        }
        return names
    }

    /**
     * v4 → v5 是纯新增：多一张 image_features，老数据一行都不能动。
     *
     * 这一版没有列被改，所以表名、列名、外键、索引必须和 Room 期望的结构一字不差 ——
     * 对不上不是迁移当场报错，而是装上新版本第一次打开数据库时抛「migration didn't
     * properly handle」把应用挡在门外。所以这里把 Room 会校验的东西全验一遍。
     */
    @Test
    fun migrationAddsImageFeatureTable() {
        val db = openV4WithData()

        EmojiDatabase.MIGRATION_4_5.migrate(db)

        assertTrue(tableExists(db, "image_features"))
        assertEquals(
            setOf("emojiId", "bytes", "modifiedAt", "sha256", "dhash", "computedAt"),
            columnsOf(db, "image_features").toSet()
        )
        assertEquals(listOf("emojiId"), primaryKeysOf(db, "image_features"))
        // 两项指纹都可空：哪个页面先跑就先算哪一项（sha256 服务「完全相同」，dhash 服务「相似」）。
        // 顺序就是实体里的声明顺序，Room 校验时只认列名与可空性，不认顺序。
        assertEquals(
            listOf("1", "1", "1", "0", "0", "1"),
            pragma(db, "PRAGMA table_info(image_features)", "notnull")
        )

        // 外键挂在 emojis 上，表情记录被删时指纹跟着走
        assertEquals(listOf("emojis"), pragma(db, "PRAGMA foreign_key_list(image_features)", "table"))
        assertEquals(listOf("emojiId"), pragma(db, "PRAGMA foreign_key_list(image_features)", "from"))
        assertEquals(listOf("id"), pragma(db, "PRAGMA foreign_key_list(image_features)", "to"))
        assertEquals(
            listOf("CASCADE"),
            pragma(db, "PRAGMA foreign_key_list(image_features)", "on_delete")
        )
        assertEquals(
            listOf("NO ACTION"),
            pragma(db, "PRAGMA foreign_key_list(image_features)", "on_update")
        )

        // 两个索引，都不是唯一索引，列名与实体里写的一致
        assertTrue(indexExists(db, "index_image_features_emojiId"))
        assertTrue(indexExists(db, "index_image_features_sha256"))
        assertEquals(
            listOf("0", "0"),
            pragma(db, "PRAGMA index_list(image_features)", "unique")
        )
        assertEquals(
            listOf("emojiId"),
            pragma(db, "PRAGMA index_info(index_image_features_emojiId)", "name")
        )
        assertEquals(
            listOf("sha256"),
            pragma(db, "PRAGMA index_info(index_image_features_sha256)", "name")
        )

        // 老数据一行不少，新表是空的
        assertEquals(2, count(db, "emojis"))
        assertEquals(2, count(db, "tags"))
        assertEquals(2, count(db, "emoji_tags"))
        assertEquals(0, count(db, "image_features"))
    }
}
