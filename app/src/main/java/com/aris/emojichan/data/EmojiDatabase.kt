package com.aris.emojichan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EmojiEntity::class,
        TagEntity::class,
        EmojiTagCrossRef::class
    ],
    version = 4,
    exportSchema = false
)
abstract class EmojiDatabase : RoomDatabase() {
    abstract fun emojiDao(): EmojiDao

    companion object {
        @Volatile
        private var INSTANCE: EmojiDatabase? = null

        /**
         * v1 → v2：新增分类表。
         *
         * 最后一句把已有表情里出现过的分类登记进新表，否则老用户升级后
         * 会看到分类栏变空（分类表是空的，表情表又还没被读过）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                db.execSQL(
                    "INSERT OR IGNORE INTO `categories` (`name`, `createTime`) " +
                        "SELECT DISTINCT `category`, 0 FROM `emojis` WHERE `category` IS NOT NULL"
                )
            }
        }

        /**
         * v2 → v3：「分类」改成「包 + 标签」，默认包改名「未分类」。
         *
         * 三件事：
         * 1. `emojis.tags` 这列删掉 —— 标签改用 tags / emoji_tags 两张真表。
         *    留着一列就是两份真相。SQLite 的 DROP COLUMN 版本要求高、还动不了索引，
         *    所以走标准的重建式迁移：建新表 → 拷数据 → 删旧表 → 改名 → 补索引。
         * 2. `categories` 加 lastUsedTime（包列表按使用情况排序要用的排序键）。
         * 3. 老的「默认」包改名「未分类」，表情表里的取值一起改。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 重建 emojis：去掉 tags 列（列的顺序与 Room 生成的建表语句一致）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emojis_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`fileType` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`isFavorite` INTEGER NOT NULL, " +
                        "`usageCount` INTEGER NOT NULL, " +
                        "`lastUsedTime` INTEGER NOT NULL, " +
                        "`createTime` INTEGER NOT NULL, " +
                        "`fileSize` INTEGER NOT NULL, " +
                        "`width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `emojis_new` (`id`, `name`, `filePath`, `fileType`, `category`, " +
                        "`source`, `isFavorite`, `usageCount`, `lastUsedTime`, `createTime`, " +
                        "`fileSize`, `width`, `height`) " +
                        "SELECT `id`, `name`, `filePath`, `fileType`, `category`, `source`, " +
                        "`isFavorite`, `usageCount`, `lastUsedTime`, `createTime`, `fileSize`, " +
                        "`width`, `height` FROM `emojis`"
                )
                db.execSQL("DROP TABLE `emojis`")
                db.execSQL("ALTER TABLE `emojis_new` RENAME TO `emojis`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_category` ON `emojis` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_source` ON `emojis` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_isFavorite` ON `emojis` (`isFavorite`)")

                // 2. 老默认包改名；若「未分类」已经存在，就把「默认」那行删掉，避免撞 UNIQUE 索引
                //    这两个名字在 v0.1.315 已从产品里删掉，迁移里保留字面量只为让老库还能一路升上来
                db.execSQL(
                    "DELETE FROM `categories` WHERE `name` = '默认' " +
                        "AND EXISTS (SELECT 1 FROM `categories` WHERE `name` = '未分类')"
                )
                db.execSQL("UPDATE `categories` SET `name` = '未分类' WHERE `name` = '默认'")
                db.execSQL("UPDATE `emojis` SET `category` = '未分类' WHERE `category` = '默认'")

                // 3. 包表加排序键
                db.execSQL(
                    "ALTER TABLE `categories` ADD COLUMN `lastUsedTime` INTEGER NOT NULL DEFAULT 0"
                )

                // 4. 标签表
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createTime` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")

                // 5. 表情 ↔ 标签 挂载表（两个外键都 ON DELETE CASCADE，字段顺序与实体一致）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emoji_tags` (" +
                        "`emojiId` INTEGER NOT NULL, " +
                        "`tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`emojiId`, `tagId`), " +
                        "FOREIGN KEY(`emojiId`) REFERENCES `emojis`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emoji_tags_emojiId` ON `emoji_tags` (`emojiId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emoji_tags_tagId` ON `emoji_tags` (`tagId`)"
                )
            }
        }

        /**
         * v3 → v4：删掉「包」，标签成为唯一的组织方式。
         *
         * 旧包数据按用户的选择**整体丢弃**：表情变成一个标签都不挂，包名不再保留
         * （表情原本的标签挂载不受影响）。
         *
         * 两个坑：
         * 1. Room 会拿实体的列清单去校验表结构，**多一列都算迁移失败**，所以必须真把
         *    `category` 列删掉，不能只在代码里拿掉。SQLite 的 DROP COLUMN 动不了索引，
         *    仍旧走重建式迁移（同 v2 → v3）。
         * 2. `emoji_tags` 对 `emojis` 有 ON DELETE CASCADE 外键，而 DROP TABLE 在外键
         *    打开时会先做一次隐式 DELETE —— 挂载关系会被级联清空。所以先把挂载关系抄进
         *    一张没有外键的临时表，重建完再抄回来。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 备份「表情 ↔ 标签」的挂载关系
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emoji_tags_backup` (" +
                        "`emojiId` INTEGER NOT NULL, " +
                        "`tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`emojiId`, `tagId`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `emoji_tags_backup` (`emojiId`, `tagId`) " +
                        "SELECT `emojiId`, `tagId` FROM `emoji_tags`"
                )

                // 2. 重建 emojis：去掉 category 列（列的顺序与 Room 生成的建表语句一致）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emojis_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`fileType` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`isFavorite` INTEGER NOT NULL, " +
                        "`usageCount` INTEGER NOT NULL, " +
                        "`lastUsedTime` INTEGER NOT NULL, " +
                        "`createTime` INTEGER NOT NULL, " +
                        "`fileSize` INTEGER NOT NULL, " +
                        "`width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `emojis_new` (`id`, `name`, `filePath`, `fileType`, " +
                        "`source`, `isFavorite`, `usageCount`, `lastUsedTime`, `createTime`, " +
                        "`fileSize`, `width`, `height`) " +
                        "SELECT `id`, `name`, `filePath`, `fileType`, `source`, " +
                        "`isFavorite`, `usageCount`, `lastUsedTime`, `createTime`, " +
                        "`fileSize`, `width`, `height` FROM `emojis`"
                )
                db.execSQL("DROP TABLE `emojis`")
                db.execSQL("ALTER TABLE `emojis_new` RENAME TO `emojis`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_source` ON `emojis` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_emojis_isFavorite` ON `emojis` (`isFavorite`)")

                // 3. 挂载关系抄回来，再扔掉临时表与包表
                db.execSQL(
                    "INSERT OR IGNORE INTO `emoji_tags` (`emojiId`, `tagId`) " +
                        "SELECT `emojiId`, `tagId` FROM `emoji_tags_backup`"
                )
                db.execSQL("DROP TABLE `emoji_tags_backup`")
                db.execSQL("DROP TABLE IF EXISTS `categories`")
            }
        }

        fun getDatabase(context: Context): EmojiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EmojiDatabase::class.java,
                    "emoji_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
