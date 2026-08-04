package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v2 only introduced [MemoryEntity].
 *
 * Some old imported backups have a stale `user_version` while already containing
 * tables or columns from a later fork. Keep this step limited to the actual v1 ->
 * v2 delta so Room never retries unrelated `ALTER TABLE` statements on such a
 * recovered database.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MemoryEntity` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `content` TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}
