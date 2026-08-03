package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the optional existing-conversation target for scheduled jobs.
 *
 * Imported fork backups can already contain this column while retaining a v27
 * user_version. Checking the live schema keeps that recovery path idempotent.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        var hasTargetConversationId = false
        db.query("PRAGMA table_info(`scheduled_jobs`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (nameIndex >= 0 && cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "targetConversationId") {
                    hasTargetConversationId = true
                    break
                }
            }
        }
        if (!hasTargetConversationId) {
            db.execSQL("ALTER TABLE `scheduled_jobs` ADD COLUMN `targetConversationId` TEXT")
        }
    }
}
