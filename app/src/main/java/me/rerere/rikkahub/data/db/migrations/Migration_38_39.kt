package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Durable command authority snapshot. Null legacy rows deliberately cannot regain elevation. */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasAuthoritySubjectId = db.query("PRAGMA table_info(`pending_chat_commands`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (nameIndex >= 0 && cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "authoritySubjectId") {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasAuthoritySubjectId) {
            db.execSQL("ALTER TABLE `pending_chat_commands` ADD COLUMN `authoritySubjectId` TEXT")
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_authoritySubjectId` " +
                "ON `pending_chat_commands` (`authoritySubjectId`)",
        )
    }
}
