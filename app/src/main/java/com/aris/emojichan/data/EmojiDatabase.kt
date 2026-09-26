package com.aris.emojichan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EmojiEntity::class, CategoryEntity::class],
    version = 2,
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

        fun getDatabase(context: Context): EmojiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EmojiDatabase::class.java,
                    "emoji_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}