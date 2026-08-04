package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_BACKFILL_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_PORTABLE_CREATE_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_TRIGGER_SQL

/**
 * Reconciles a database file that was just restored from a backup so Room can open it.
 *
 * The fork added several tables (scheduled jobs, workflows, ssh hosts, telegram chats,
 * the agent-run ledger) on top of upstream RikkaHub. A backup exported from *upstream*
 * RikkaHub does not contain those tables, yet upstream and the fork share the same Room
 * schema version number. When such a backup is restored, Room reopens the file at the
 * matching version, runs no migration, and then either fails its integrity check or hits
 * "no such table: scheduled_jobs" at first query — the app crashes on the very first launch
 * after the import (see issue #8).
 *
 * This step runs once, right after the restore writes `rikka_hub.db`, on the raw file before
 * Room touches it:
 *  - It creates any of the fork-only tables that are missing, empty, with the exact current
 *    schema Room expects (copied verbatim from the current exported schema) — so the file looks
 *    like a clean agent install for those tables.
 *  - If the file is already stamped at the current schema version (so Room would run no
 *    migration), it rewrites Room's identity row to the fork's expected hash. Without this,
 *    Room rejects the foreign hash even though every table is now present. The shared tables
 *    already match because the fork tracks upstream's schema, so trusting the hash is sound.
 *  - If the file is at an older version (upgrade scenario, e.g. official v24 to agent v30),
 *    it keeps the original user_version so Room runs every real migration, including the
 *    explicit 28→29 migration. The fork-only tables are pre-created only as compatibility
 *    scaffolding for upstream backups.
 *
 * Room then runs its normal migrations up to current and sets the identity itself; the
 *    pre-created tables simply let those migrations find the fork-only schema.
 * Backups newer than the app are left untouched (Room will report the downgrade).
 *
 * Best-effort: any failure here is logged and swallowed so a restore never half-breaks. The
 * worst case is the same pre-existing crash on next open, never data loss — there is no
 * destructive-migration fallback configured, so the restored rows always survive on disk.
 */
object ImportedDatabaseReconciler {

    private const val TAG = "DbReconciler"
    private const val DB_NAME = "rikka_hub"

    /**
     * Room's schema version and identity hash for [AppDatabase]. Both are copied verbatim
     * from app/schemas/me.rerere.rikkahub.data.db.AppDatabase/42.json. When the schema
     * version is bumped, update BOTH constants (and the table DDL below if the fork-only
     * tables changed) or this reconciliation will silently stop matching.
     */
    internal const val EXPECTED_VERSION = 42
    internal const val EXPECTED_IDENTITY_HASH = "1cee9962080483881bef799c83219b40"
    internal const val PRE_STORAGE_MODE_V35_IDENTITY_HASH = "2a74d694211f0df9f9094c7571ec71dd"

    internal enum class ReconcilePlan {
        SKIP,
        CURRENT_V35_DELTA,
        FULL_COMPATIBILITY,
    }

    /**
     * Same-version development builds cannot use a Room migration. Recognise the one v35
     * schema that was installed before workspace storage_mode was added and apply only that
     * additive delta. This path must not touch MemoryEntity: its FTS5 triggers are backed by
     * the bundled requery SQLite runtime, while this pre-Room reconciler necessarily opens the
     * file through the device framework SQLite (which does not provide FTS5 on some devices).
     */
    internal fun reconcilePlan(version: Int, identityHash: String?): ReconcilePlan = when {
        version > EXPECTED_VERSION -> ReconcilePlan.SKIP
        version == EXPECTED_VERSION && identityHash == EXPECTED_IDENTITY_HASH ->
            ReconcilePlan.SKIP
        version == EXPECTED_VERSION && version == 35 &&
            identityHash == PRE_STORAGE_MODE_V35_IDENTITY_HASH ->
            ReconcilePlan.CURRENT_V35_DELTA
        else -> ReconcilePlan.FULL_COMPATIBILITY
    }

    /**
     * Fork-only tables absent from an upstream backup, with their exact current create + index
     * statements. Every statement is IF NOT EXISTS so running it against a genuine agent
     * backup (where the tables already exist) is a no-op.
     */
    private val FORK_ONLY_DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `scheduled_jobs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT, `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER, `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER, `mode` TEXT NOT NULL DEFAULT 'llm', `actionsJson` TEXT, `cronExpression` TEXT, `timezone` TEXT, `startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER, `runsSoFar` INTEGER NOT NULL DEFAULT 0, `catchup` TEXT NOT NULL DEFAULT 'fire_once', `description` TEXT, `tags` TEXT, `targetConversationId` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `scheduled_job_runs` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, `mode` TEXT NOT NULL, `scheduledAtMs` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `outcome` TEXT NOT NULL, `conversationId` TEXT, `errorMessage` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `ssh_hosts` (`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, `password` TEXT, `privateKey` TEXT, `passphrase` TEXT, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        "CREATE TABLE IF NOT EXISTS `telegram_chats` (`chatId` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastMessageAtMs` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `enabled` INTEGER NOT NULL DEFAULT 1, `definitionJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `lastRunStatus` TEXT, `lastRunError` TEXT, `runsTodayCount` INTEGER NOT NULL DEFAULT 0, `runsTodayDate` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `workflow_runs` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` TEXT NOT NULL, `firedAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `errorMessage` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_firedAtMs` ON `workflow_runs` (`workflowId`, `firedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `domain_id` TEXT NOT NULL, `parent_run_id` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `finished_at_ms` INTEGER, `last_error` TEXT, `metadata_json` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)",
        "CREATE TABLE IF NOT EXISTS `execution_records` (`id` TEXT NOT NULL, `trace_id` TEXT NOT NULL, `parent_execution_id` TEXT, `command_id` TEXT, `conversation_id` TEXT, `subject_id` TEXT NOT NULL, `subject_type` TEXT NOT NULL, `origin` TEXT NOT NULL, `capability_keys` TEXT NOT NULL, `resource_summary` TEXT NOT NULL, `runtime` TEXT NOT NULL, `idempotency_key` TEXT, `runtime_handle_summary` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `heartbeat_at_ms` INTEGER, `finished_at_ms` INTEGER, `cancellation_result` TEXT, `terminal_detail` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_execution_records_status` ON `execution_records` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_execution_records_trace` ON `execution_records` (`trace_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_execution_records_parent` ON `execution_records` (`parent_execution_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_execution_records_idempotency` ON `execution_records` (`idempotency_key`)",
        "CREATE INDEX IF NOT EXISTS `idx_execution_records_updated` ON `execution_records` (`updated_at_ms`)",
        "CREATE TABLE IF NOT EXISTS `capability_grants` (`id` TEXT NOT NULL, `subject_id` TEXT NOT NULL, `subject_type` TEXT NOT NULL, `capability_key` TEXT NOT NULL, `resource_kind` TEXT NOT NULL, `resource_identifier` TEXT NOT NULL, `allowed_origins` TEXT NOT NULL, `scope` TEXT NOT NULL, `expires_at_ms` INTEGER, `revoked` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_capability_grants_subject` ON `capability_grants` (`subject_id`, `subject_type`)",
        "CREATE INDEX IF NOT EXISTS `idx_capability_grants_active` ON `capability_grants` (`revoked`, `expires_at_ms`)",
        "CREATE TABLE IF NOT EXISTS `alarms` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `note` TEXT, `scheduleType` TEXT NOT NULL, `time` TEXT, `hour` INTEGER, `minute` INTEGER, `daysOfWeek` TEXT, `timezone` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastFiredAtMs` INTEGER, `nextFireAtMs` INTEGER, PRIMARY KEY(`id`))",
        "DROP INDEX IF EXISTS `index_alarms_enabled_nextFireAtMs`",
        "CREATE TABLE IF NOT EXISTS `pending_chat_commands` (" +
            "`id` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `authoritySubjectId` TEXT, " +
            "`type` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `state` TEXT NOT NULL, " +
            "`priority` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, " +
            "`expectedTargetVersion` INTEGER, `expectedBranchHeadMessageId` TEXT, `dedupeKey` TEXT, " +
            "`idempotencyKey` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `claimedBy` TEXT, " +
            "`leaseUntil` INTEGER, `createdAt` INTEGER NOT NULL, `startedAt` INTEGER, " +
            "`finishedAt` INTEGER, `expiresAt` INTEGER, `lastErrorCode` TEXT, `lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_conversationId` " +
            "ON `pending_chat_commands` (`conversationId`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_conversationId_state_priority_sequence` " +
            "ON `pending_chat_commands` (`conversationId`, `state`, `priority`, `sequence`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_leaseUntil` " +
            "ON `pending_chat_commands` (`leaseUntil`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_dedupeKey` " +
            "ON `pending_chat_commands` (`dedupeKey`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_authoritySubjectId` " +
            "ON `pending_chat_commands` (`authoritySubjectId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_chat_commands_idempotencyKey` " +
            "ON `pending_chat_commands` (`idempotencyKey`)",
        "CREATE TABLE IF NOT EXISTS `memory_captures` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `scope_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `user_message_id` TEXT NOT NULL, `assistant_message_id` TEXT NOT NULL, `origin` TEXT NOT NULL, `capture_source` TEXT NOT NULL DEFAULT 'AUTOMATIC_TURN', `auto_save_mode` TEXT NOT NULL, `user_text` TEXT NOT NULL, `assistant_text` TEXT NOT NULL, `state` TEXT NOT NULL DEFAULT 'PENDING', `retry_count` INTEGER NOT NULL DEFAULT 0, `last_error_code` TEXT, `last_error_message` TEXT, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `lease_owner` TEXT, `lease_until_ms` INTEGER, `processed_at_ms` INTEGER, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_captures_conversation_id_assistant_message_id_capture_source` ON `memory_captures` (`conversation_id`, `assistant_message_id`, `capture_source`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_captures_scope_id_state_created_at_ms` ON `memory_captures` (`scope_id`, `state`, `created_at_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_captures_lease_until_ms` ON `memory_captures` (`lease_until_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_captures_conversation_id` ON `memory_captures` (`conversation_id`)",
        "CREATE TABLE IF NOT EXISTS `memory_candidates` (`id` TEXT NOT NULL, `scope_id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `source_conversation_id` TEXT NOT NULL, `capture_ids_json` TEXT NOT NULL, `action` TEXT NOT NULL, `target_memory_ids_json` TEXT NOT NULL, `expected_revisions_json` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `memory_kind` TEXT NOT NULL, `tags_json` TEXT NOT NULL, `importance` REAL NOT NULL, `confidence` REAL NOT NULL, `expires_at_ms` INTEGER, `risk_flags_json` TEXT NOT NULL, `reason` TEXT NOT NULL, `evidence_message_ids_json` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING_REVIEW', `applied_memory_id` INTEGER, `resolution_error` TEXT, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_memory_candidates_scope_id_status_created_at_ms` ON `memory_candidates` (`scope_id`, `status`, `created_at_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_candidates_source_conversation_id` ON `memory_candidates` (`source_conversation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_candidates_applied_memory_id` ON `memory_candidates` (`applied_memory_id`)",
        "CREATE TABLE IF NOT EXISTS `memory_revisions` (`id` TEXT NOT NULL, `memory_id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `operation` TEXT NOT NULL, `before_snapshot_json` TEXT, `after_snapshot_json` TEXT, `actor` TEXT NOT NULL, `candidate_id` TEXT, `source_conversation_id` TEXT, `source_message_ids_json` TEXT NOT NULL DEFAULT '[]', `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_revisions_memory_id_revision` ON `memory_revisions` (`memory_id`, `revision`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_revisions_memory_id_created_at_ms` ON `memory_revisions` (`memory_id`, `created_at_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_revisions_candidate_id` ON `memory_revisions` (`candidate_id`)",
    )

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(table),
    ).use { cursor -> cursor.moveToFirst() }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet {
                if (nameIndex >= 0) while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    /** Remove only fork-only tables that cannot legally exist at an older user_version. */
    private fun dropPrematureForkTables(db: SQLiteDatabase, version: Int) {
        val introductions = listOf(
            19 to "ssh_hosts",
            20 to "telegram_chats",
            21 to "scheduled_job_runs",
            22 to "workflows",
            22 to "workflow_runs",
            24 to "agent_runs",
            26 to "workspaces",
            27 to "alarms",
            29 to "pending_chat_commands",
            31 to "memory_captures",
            31 to "memory_candidates",
            31 to "memory_revisions",
            31 to "memory_evidence",
            31 to "memory_links",
            32 to "memory_relation_candidates",
            32 to "memory_backfill_runs",
            35 to "execution_records",
            35 to "capability_grants",
        )
        introductions.filter { (introduced, _) -> version < introduced }.forEach { (_, table) ->
            moveTableToLegacyRecovery(db, table)
        }
    }

    private fun moveTableToLegacyRecovery(db: SQLiteDatabase, table: String): String? {
        if (!tableExists(db, table)) return null
        // SQLite index names are database-global and are not renamed when their table is
        // renamed. Drop only the recovery table's secondary indexes so Room can recreate
        // the canonical indexes on the historical table without name collisions.
        db.rawQuery("PRAGMA index_list(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex >= 0) {
                val indexes = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                indexes.filterNot { it.startsWith("sqlite_autoindex_") }.forEach {
                    db.execSQL("DROP INDEX IF EXISTS `$it`")
                }
            }
        }
        var recovery = "${table}_legacy_recovery"
        var suffix = 2
        while (tableExists(db, recovery)) {
            recovery = "${table}_legacy_recovery_${suffix++}"
        }
        db.execSQL("ALTER TABLE `$table` RENAME TO `$recovery`")
        return recovery
    }

    private fun normalizeLegacyWorkspaces(db: SQLiteDatabase) {
        if (!tableExists(db, "workspaces")) return
        if ("storage_mode" !in tableColumns(db, "workspaces")) return
        val recovery = moveTableToLegacyRecovery(db, "workspaces") ?: return
        val columns = listOf("id", "name", "root", "shell_status", "created_at", "updated_at", "last_access_at", "tool_approvals")
        db.execSQL("""
            CREATE TABLE `workspaces` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL,
                `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                `last_access_at` INTEGER, `tool_approvals` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`)
            )
        """.trimIndent())
        val existing = tableColumns(db, recovery)
        val common = columns.filter(existing::contains)
        if (common.isNotEmpty()) {
            val quoted = common.joinToString(", ") { "`$it`" }
            db.execSQL("INSERT INTO `workspaces` ($quoted) SELECT $quoted FROM `$recovery`")
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")
    }

    private fun dropPrematureScheduledJobsTable(db: SQLiteDatabase) {
        // Preserve any unexpected rows rather than deleting them; Room can ignore the
        // recovery table while its numbered migration recreates the historical table.
        moveTableToLegacyRecovery(db, "scheduled_jobs")
    }

    /** Restore the schema valid at the declared version before Room runs numbered migrations. */
    private fun normalizeLegacyScheduledJobs(db: SQLiteDatabase, version: Int) {
        if (!tableExists(db, "scheduled_jobs")) return
        val existing = tableColumns(db, "scheduled_jobs")
        val legacyV20 = version < 21
        val needsRebuild = if (legacyV20) {
            existing.any {
                it in setOf(
                    "mode", "actionsJson", "cronExpression", "timezone", "startAtUnixMs",
                    "endAtUnixMs", "maxRuns", "runsSoFar", "catchup", "description", "tags",
                    "targetConversationId",
                )
            }
        } else {
            version < 28 && "targetConversationId" in existing
        }
        if (!needsRebuild) return
        val columns = if (legacyV20) {
            listOf("id", "name", "prompt", "assistantId", "scheduleType", "atUnixMs", "intervalSeconds", "enabled", "createdAtMs", "lastRunAtMs", "nextRunAtMs")
        } else {
            listOf("id", "name", "prompt", "assistantId", "scheduleType", "atUnixMs", "intervalSeconds", "enabled", "createdAtMs", "lastRunAtMs", "nextRunAtMs", "mode", "actionsJson", "cronExpression", "timezone", "startAtUnixMs", "endAtUnixMs", "maxRuns", "runsSoFar", "catchup", "description", "tags")
        }
        val create = if (legacyV20) """
            `id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT,
            `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER,
            `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL,
            `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER, PRIMARY KEY(`id`)
        """.trimIndent() else """
            `id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT,
            `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER,
            `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL,
            `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER,
            `mode` TEXT NOT NULL DEFAULT 'llm', `actionsJson` TEXT, `cronExpression` TEXT,
            `timezone` TEXT, `startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER,
            `runsSoFar` INTEGER NOT NULL DEFAULT 0, `catchup` TEXT NOT NULL DEFAULT 'fire_once',
            `description` TEXT, `tags` TEXT, PRIMARY KEY(`id`)
        """.trimIndent()
        val recovery = moveTableToLegacyRecovery(db, "scheduled_jobs") ?: return
        val common = columns.filter(tableColumns(db, recovery)::contains)
        db.execSQL("DROP TABLE IF EXISTS `scheduled_jobs_legacy`")
        db.execSQL("CREATE TABLE `scheduled_jobs_legacy` ($create)")
        if (common.isNotEmpty()) {
            val quoted = common.joinToString(", ") { "`$it`" }
            db.execSQL("INSERT INTO `scheduled_jobs_legacy` ($quoted) SELECT $quoted FROM `$recovery`")
        }
        db.execSQL("ALTER TABLE `scheduled_jobs_legacy` RENAME TO `scheduled_jobs`")
    }

    private fun dropPrematurePendingChatCommandsTable(db: SQLiteDatabase) {
        moveTableToLegacyRecovery(db, "pending_chat_commands")
    }

    private fun ensureConversationFolderV29Column(db: SQLiteDatabase) {
        val hasFolderId = db.rawQuery("PRAGMA table_info(`ConversationEntity`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) {
                false
            } else {
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "folder_id") {
                        found = true
                        break
                    }
                }
                found
            }
        }
        if (!hasFolderId) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    private fun ensureScheduledJobsV29Column(db: SQLiteDatabase) {
        val hasTargetConversationId = db.rawQuery("PRAGMA table_info(`scheduled_jobs`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) {
                false
            } else {
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "targetConversationId") {
                        found = true
                        break
                    }
                }
                found
            }
        }
        if (!hasTargetConversationId) {
            db.execSQL("ALTER TABLE `scheduled_jobs` ADD COLUMN `targetConversationId` TEXT")
        }
    }

    private fun ensureMemoryV31Columns(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(`MemoryEntity`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet {
                if (nameIndex >= 0) {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }
        if ("title" !in columns) {
            db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `title` TEXT")
        }
        if ("updated_at_ms" !in columns) {
            db.execSQL(
                "ALTER TABLE `MemoryEntity` ADD COLUMN `updated_at_ms` INTEGER NOT NULL DEFAULT 0",
            )
        }
        if ("importance" !in columns) {
            db.execSQL(
                "ALTER TABLE `MemoryEntity` ADD COLUMN `importance` REAL NOT NULL DEFAULT 0.5",
            )
        }
        val additions = listOf(
            "created_at_ms" to "INTEGER NOT NULL DEFAULT 0",
            "last_accessed_at_ms" to "INTEGER",
            "expires_at_ms" to "INTEGER",
            "memory_kind" to "TEXT NOT NULL DEFAULT 'OTHER'",
            "confidence" to "REAL NOT NULL DEFAULT 1.0",
            "tags_json" to "TEXT NOT NULL DEFAULT '[]'",
            "tags_search" to "TEXT NOT NULL DEFAULT ''",
            "content_hash" to "TEXT NOT NULL DEFAULT ''",
            "source_type" to "TEXT NOT NULL DEFAULT 'LEGACY'",
            "source_conversation_id" to "TEXT",
            "source_message_ids_json" to "TEXT NOT NULL DEFAULT '[]'",
            "lifecycle_status" to "TEXT NOT NULL DEFAULT 'ACTIVE'",
            "approval_source" to "TEXT NOT NULL DEFAULT 'LEGACY'",
            "revision" to "INTEGER NOT NULL DEFAULT 1",
        )
        additions.forEach { (name, declaration) ->
            if (name !in columns) {
                db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `$name` $declaration")
            }
        }
        db.execSQL(
            "UPDATE `MemoryEntity` SET `created_at_ms` = " +
                "CASE WHEN `updated_at_ms` > 0 THEN `updated_at_ms` ELSE ? END " +
                "WHERE `created_at_ms` = 0",
            arrayOf(System.currentTimeMillis()),
        )
    }

    /** Supports an early v31 Memory V2 preview database restored before source isolation existed. */
    private fun ensureMemoryV31CaptureColumns(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(`memory_captures`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet {
                if (nameIndex >= 0) while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        if ("capture_source" !in columns) {
            db.execSQL(
                "ALTER TABLE `memory_captures` ADD COLUMN `capture_source` " +
                    "TEXT NOT NULL DEFAULT 'AUTOMATIC_TURN'",
            )
        }
        db.execSQL(
            "DROP INDEX IF EXISTS `index_memory_captures_conversation_id_assistant_message_id`",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_memory_captures_conversation_id_assistant_message_id_capture_source` " +
            "ON `memory_captures` (`conversation_id`, `assistant_message_id`, `capture_source`)",
        )
    }

    /**
     * Upstream and agent builds can share a Room user_version while only the agent has these
     * tables. For a restored database already at a later schema step, Room will not replay the
     * missing intermediate migration, so provide the same idempotent scaffolding here.
     */
    private fun ensureMemoryV32Schema(db: SQLiteDatabase) {
        ensureColumns(
            db,
            "MemoryEntity",
            listOf(
                "origin_assistant_id" to "TEXT",
                "attribution" to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
                "truth_status" to "TEXT NOT NULL DEFAULT 'CONFIRMED'",
                "occurred_at_ms" to "INTEGER",
                "participants_json" to "TEXT NOT NULL DEFAULT '[]'",
                "outcome" to "TEXT",
            ),
        )
        ensureColumns(
            db,
            "memory_candidates",
            listOf(
                "proposal_key" to "TEXT",
                "attribution" to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
                "truth_status" to "TEXT NOT NULL DEFAULT 'CONFIRMED'",
                "occurred_at_ms" to "INTEGER",
                "participants_json" to "TEXT NOT NULL DEFAULT '[]'",
                "outcome" to "TEXT",
            ),
        )
        ensureColumns(
            db,
            "memory_captures",
            listOf(
                "processing_outcome" to "TEXT",
                "candidate_count" to "INTEGER NOT NULL DEFAULT 0",
                "supersedes_capture_id" to "TEXT",
                "narrative_events_enabled" to "INTEGER NOT NULL DEFAULT 0",
                "insights_theories_enabled" to "INTEGER NOT NULL DEFAULT 0",
            ),
        )
        listOf(
            "CREATE TABLE IF NOT EXISTS `memory_evidence` (`id` TEXT NOT NULL, `memory_id` INTEGER, `candidate_id` TEXT, `conversation_id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `role` TEXT NOT NULL, `excerpt` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `captured_at_ms` INTEGER NOT NULL, `quality` TEXT NOT NULL DEFAULT 'ORIGINAL_MESSAGE', PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_memory_evidence_memory_id` ON `memory_evidence` (`memory_id`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_evidence_candidate_id` ON `memory_evidence` (`candidate_id`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_evidence_message_id` ON `memory_evidence` (`message_id`)",
            "CREATE TABLE IF NOT EXISTS `memory_links` (`id` TEXT NOT NULL, `source_memory_id` INTEGER NOT NULL, `target_memory_id` INTEGER NOT NULL, `relation_type` TEXT NOT NULL, `weight` REAL NOT NULL, `description` TEXT NOT NULL, `evidence_message_ids_json` TEXT NOT NULL, `created_by_assistant_id` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `revision` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_links_source_memory_id_target_memory_id_relation_type` ON `memory_links` (`source_memory_id`, `target_memory_id`, `relation_type`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_links_target_memory_id` ON `memory_links` (`target_memory_id`)",
            "CREATE TABLE IF NOT EXISTS `memory_relation_candidates` (`id` TEXT NOT NULL, `batch_id` TEXT NOT NULL, `source_proposal_key` TEXT, `source_memory_id` INTEGER, `target_proposal_key` TEXT, `target_memory_id` INTEGER, `relation_type` TEXT NOT NULL, `weight` REAL NOT NULL, `description` TEXT NOT NULL, `evidence_message_ids_json` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_batch_id` ON `memory_relation_candidates` (`batch_id`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_status` ON `memory_relation_candidates` (`status`)",
            "CREATE TABLE IF NOT EXISTS `memory_backfill_runs` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `scope_id` TEXT NOT NULL, `selection_json` TEXT NOT NULL, `total_turns` INTEGER NOT NULL, `processed_turns` INTEGER NOT NULL DEFAULT 0, `failed_turns` INTEGER NOT NULL DEFAULT 0, `status` TEXT NOT NULL DEFAULT 'PENDING', `last_error` TEXT, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_memory_backfill_runs_assistant_id` ON `memory_backfill_runs` (`assistant_id`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_backfill_runs_status` ON `memory_backfill_runs` (`status`)",
        ).forEach(db::execSQL)
        rebuildPortableMemoryFts(db)
    }

    private fun ensureMemoryV33Schema(db: SQLiteDatabase) {
        ensureColumns(
            db,
            "memory_captures",
            listOf("context_turn_limit" to "INTEGER NOT NULL DEFAULT 12"),
        )
    }

    private fun ensureBrowserV34Schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `browser_bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalized_url` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_browser_bookmarks_normalized_url` ON `browser_bookmarks` (`normalized_url`)",
            "CREATE TABLE IF NOT EXISTS `browser_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalized_url` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `visited_at_ms` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_browser_history_normalized_url` ON `browser_history` (`normalized_url`)",
            "CREATE INDEX IF NOT EXISTS `index_browser_history_visited_at_ms` ON `browser_history` (`visited_at_ms`)",
        ).forEach(db::execSQL)
    }

    private fun ensureExecutionV35Schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `execution_records` (`id` TEXT NOT NULL, `trace_id` TEXT NOT NULL, `parent_execution_id` TEXT, `command_id` TEXT, `conversation_id` TEXT, `subject_id` TEXT NOT NULL, `subject_type` TEXT NOT NULL, `origin` TEXT NOT NULL, `capability_keys` TEXT NOT NULL, `resource_summary` TEXT NOT NULL, `runtime` TEXT NOT NULL, `idempotency_key` TEXT, `runtime_handle_summary` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `heartbeat_at_ms` INTEGER, `finished_at_ms` INTEGER, `cancellation_result` TEXT, `terminal_detail` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_status` ON `execution_records` (`status`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_trace` ON `execution_records` (`trace_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_parent` ON `execution_records` (`parent_execution_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_idempotency` ON `execution_records` (`idempotency_key`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_updated` ON `execution_records` (`updated_at_ms`)",
        ).forEach(db::execSQL)
    }

    /** Same-version upstream v37 backups need the complete fork execution schema before Room opens. */
    private fun ensureExecutionV37Schema(db: SQLiteDatabase) {
        ensureColumns(
            db,
            "execution_records",
            listOf(
                "execution_kind" to "TEXT NOT NULL DEFAULT 'TOOL_CALL'",
                "state_version" to "INTEGER NOT NULL DEFAULT 0",
                "last_state_source" to "TEXT NOT NULL DEFAULT 'LEGACY'",
                "last_reason_code" to "TEXT",
                "verification_state" to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
                "last_probe_at_ms" to "INTEGER",
                "completion_policy" to "TEXT NOT NULL DEFAULT 'WAIT_FOR_CHILDREN'",
                "runtime_instance_marker" to "TEXT",
                "cancellation_requested_at_ms" to "INTEGER",
                "requested_terminal_outcome" to "TEXT NOT NULL DEFAULT 'NONE'",
            ),
        )
        listOf(
            "CREATE TABLE IF NOT EXISTS `execution_events` (`event_id` TEXT NOT NULL, " +
                "`execution_id` TEXT NOT NULL, `sequence` INTEGER NOT NULL, " +
                "`previous_status` TEXT, `next_status` TEXT NOT NULL, " +
                "`previous_verification` TEXT, `next_verification` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `reason_code` TEXT, `created_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`event_id`), FOREIGN KEY(`execution_id`) REFERENCES " +
                "`execution_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS `pending_tool_approvals` (`approval_id` TEXT NOT NULL, " +
                "`execution_id` TEXT NOT NULL, `trace_id` TEXT, `tool_call_id` TEXT NOT NULL, " +
                "`conversation_id` TEXT NOT NULL, `subject_id` TEXT NOT NULL, " +
                "`subject_type` TEXT NOT NULL, `origin` TEXT NOT NULL, " +
                "`capability_key` TEXT NOT NULL, `resource_category` TEXT NOT NULL, " +
                "`requested_at_ms` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', " +
                "`state_version` INTEGER NOT NULL DEFAULT 0, `resolved_at_ms` INTEGER, " +
                "`resolution_reason` TEXT, `resolution_request_id` TEXT, " +
                "PRIMARY KEY(`approval_id`))",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_conversation_status_updated` " +
                "ON `execution_records` (`conversation_id`, `status`, `updated_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_subject_status_updated` " +
                "ON `execution_records` (`subject_id`, `status`, `updated_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_parent_status` " +
                "ON `execution_records` (`parent_execution_id`, `status`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_runtime_handle` " +
                "ON `execution_records` (`runtime`, `runtime_handle_summary`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_heartbeat_status` " +
                "ON `execution_records` (`heartbeat_at_ms`, `status`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_events_execution` " +
                "ON `execution_events` (`execution_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_execution_events_execution_sequence` " +
                "ON `execution_events` (`execution_id`, `sequence`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_events_created` " +
                "ON `execution_events` (`created_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_tool_approvals_execution` " +
                "ON `pending_tool_approvals` (`execution_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_tool_approvals_conversation_status_requested` " +
                "ON `pending_tool_approvals` (`conversation_id`, `status`, `requested_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_tool_approvals_resolved` " +
                "ON `pending_tool_approvals` (`resolved_at_ms`)",
        ).forEach(db::execSQL)
    }

    /** Same-version upstream v38 backups need the fork-only pet sidecar schema before Room opens. */
    private fun ensurePetV38Schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `pet_dialogue_sessions` (`sessionId` TEXT NOT NULL, `assistantId` TEXT NOT NULL, `privilegedConversationId` TEXT NOT NULL, `localDate` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `activeOwnerKey` TEXT, `status` TEXT NOT NULL, `archiveReason` TEXT, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `notes` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, `summaryState` TEXT NOT NULL, `stateVersion` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `archivedAtMs` INTEGER, `deletedAtMs` INTEGER, PRIMARY KEY(`sessionId`))",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_assistantId` ON `pet_dialogue_sessions` (`assistantId`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_privilegedConversationId` ON `pet_dialogue_sessions` (`privilegedConversationId`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_assistantId_status` ON `pet_dialogue_sessions` (`assistantId`, `status`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_assistantId_localDate` ON `pet_dialogue_sessions` (`assistantId`, `localDate`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_activeOwnerKey` ON `pet_dialogue_sessions` (`activeOwnerKey`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_deletedAtMs` ON `pet_dialogue_sessions` (`deletedAtMs`)",
            "CREATE TABLE IF NOT EXISTS `pet_dialogue_turns` (`turnId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `inputKind` TEXT NOT NULL, `userText` TEXT, `interactionJson` TEXT, `assistantText` TEXT, `action` TEXT, `handoffRequestId` TEXT, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`turnId`), FOREIGN KEY(`sessionId`) REFERENCES `pet_dialogue_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_turns_sessionId` ON `pet_dialogue_turns` (`sessionId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pet_dialogue_turns_sessionId_sequence` ON `pet_dialogue_turns` (`sessionId`, `sequence`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_turns_handoffRequestId` ON `pet_dialogue_turns` (`handoffRequestId`)",
            "CREATE TABLE IF NOT EXISTS `pet_handoff_requests` (`requestId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `turnId` TEXT NOT NULL, `assistantId` TEXT NOT NULL, `privilegedConversationId` TEXT NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, `title` TEXT NOT NULL, `request` TEXT NOT NULL, `targetCommandId` TEXT, `stateVersion` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `submittedAtMs` INTEGER, `resolvedAtMs` INTEGER, `expiresAtMs` INTEGER, PRIMARY KEY(`requestId`), FOREIGN KEY(`sessionId`) REFERENCES `pet_dialogue_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`turnId`) REFERENCES `pet_dialogue_turns`(`turnId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_sessionId` ON `pet_handoff_requests` (`sessionId`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_turnId` ON `pet_handoff_requests` (`turnId`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_assistantId_status` ON `pet_handoff_requests` (`assistantId`, `status`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_targetCommandId` ON `pet_handoff_requests` (`targetCommandId`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_expiresAtMs` ON `pet_handoff_requests` (`expiresAtMs`)",
            "CREATE TABLE IF NOT EXISTS `pet_dialogue_revisions` (`revisionId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `actor` TEXT NOT NULL, `operation` TEXT NOT NULL, `previousTitle` TEXT NOT NULL, `previousSummary` TEXT NOT NULL, `previousNotes` TEXT NOT NULL, `previousTagsJson` TEXT NOT NULL, `previousStatus` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`revisionId`), FOREIGN KEY(`sessionId`) REFERENCES `pet_dialogue_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_revisions_sessionId` ON `pet_dialogue_revisions` (`sessionId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pet_dialogue_revisions_sessionId_revision` ON `pet_dialogue_revisions` (`sessionId`, `revision`)",
            "CREATE INDEX IF NOT EXISTS `index_pet_dialogue_revisions_createdAtMs` ON `pet_dialogue_revisions` (`createdAtMs`)",
        ).forEach(db::execSQL)
    }

    /** Additive same-version guard for backups restored from a pre-v39 fork build. */
    private fun ensurePendingCommandAuthorityV39Schema(db: SQLiteDatabase) {
        ensureColumns(
            db,
            "pending_chat_commands",
            listOf("authoritySubjectId" to "TEXT"),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_authoritySubjectId` " +
                "ON `pending_chat_commands` (`authoritySubjectId`)",
        )
    }

    /** Same-version upstream v40 backups need the private, redacted experience store. */
    private fun ensureToolExperienceV40Schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `tool_experiences` (`experience_id` TEXT NOT NULL, `authority_subject_id` TEXT NOT NULL, `primary_tool_name` TEXT NOT NULL, `tool_names_json` TEXT NOT NULL, `category_path` TEXT NOT NULL, `schema_fingerprint` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `tags_json` TEXT NOT NULL, `state` TEXT NOT NULL, `confidence` TEXT NOT NULL, `state_version` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `last_observed_at_ms` INTEGER NOT NULL, `last_verified_at_ms` INTEGER, `deleted_at_ms` INTEGER, PRIMARY KEY(`experience_id`))",
            "CREATE INDEX IF NOT EXISTS `index_tool_experiences_authority_subject_id_state_updated_at_ms` ON `tool_experiences` (`authority_subject_id`, `state`, `updated_at_ms`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_experiences_authority_subject_id_primary_tool_name_schema_fingerprint` ON `tool_experiences` (`authority_subject_id`, `primary_tool_name`, `schema_fingerprint`)",
            "CREATE INDEX IF NOT EXISTS `index_tool_experiences_primary_tool_name_state` ON `tool_experiences` (`primary_tool_name`, `state`)",
            "CREATE INDEX IF NOT EXISTS `index_tool_experiences_deleted_at_ms` ON `tool_experiences` (`deleted_at_ms`)",
            "CREATE TABLE IF NOT EXISTS `tool_experience_evidence` (`evidence_id` TEXT NOT NULL, `experience_id` TEXT NOT NULL, `execution_id` TEXT NOT NULL, `tool_name` TEXT NOT NULL, `schema_fingerprint` TEXT NOT NULL, `outcome_kind` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`evidence_id`), FOREIGN KEY(`experience_id`) REFERENCES `tool_experiences`(`experience_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS `index_tool_experience_evidence_experience_id_created_at_ms` ON `tool_experience_evidence` (`experience_id`, `created_at_ms`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_experience_evidence_execution_id` ON `tool_experience_evidence` (`execution_id`)",
            "CREATE TABLE IF NOT EXISTS `tool_experience_revisions` (`revision_id` TEXT NOT NULL, `experience_id` TEXT NOT NULL, `revision` INTEGER NOT NULL, `actor` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `tags_json` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`revision_id`), FOREIGN KEY(`experience_id`) REFERENCES `tool_experiences`(`experience_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_experience_revisions_experience_id_revision` ON `tool_experience_revisions` (`experience_id`, `revision`)",
            "CREATE INDEX IF NOT EXISTS `index_tool_experience_revisions_created_at_ms` ON `tool_experience_revisions` (`created_at_ms`)",
        ).forEach(db::execSQL)
    }

    /** Same-version upstream v41 backups need the private model-confirmed shortcut metadata. */
    private fun ensureToolShortcutV41Schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `tool_shortcuts` (`shortcut_id` TEXT NOT NULL, `authority_subject_id` TEXT NOT NULL, `tool_name` TEXT NOT NULL, `source` TEXT NOT NULL, `category_path` TEXT NOT NULL, `risk` TEXT NOT NULL, `schema_fingerprint` TEXT NOT NULL, `state` TEXT NOT NULL, `state_version` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `last_used_at_ms` INTEGER, `use_count` INTEGER NOT NULL, `model_confirmed_at_ms` INTEGER NOT NULL, PRIMARY KEY(`shortcut_id`))",
            "CREATE INDEX IF NOT EXISTS `index_tool_shortcuts_authority_subject_id_state_updated_at_ms` ON `tool_shortcuts` (`authority_subject_id`, `state`, `updated_at_ms`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_shortcuts_authority_subject_id_tool_name_schema_fingerprint` ON `tool_shortcuts` (`authority_subject_id`, `tool_name`, `schema_fingerprint`)",
            "CREATE INDEX IF NOT EXISTS `index_tool_shortcuts_tool_name_state` ON `tool_shortcuts` (`tool_name`, `state`)",
        ).forEach(db::execSQL)
    }

    private fun ensureCapabilityGrantsV35Schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `capability_grants` (`id` TEXT NOT NULL, `subject_id` TEXT NOT NULL, `subject_type` TEXT NOT NULL, `capability_key` TEXT NOT NULL, `resource_kind` TEXT NOT NULL, `resource_identifier` TEXT NOT NULL, `allowed_origins` TEXT NOT NULL, `scope` TEXT NOT NULL, `expires_at_ms` INTEGER, `revoked` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `idx_capability_grants_subject` ON `capability_grants` (`subject_id`, `subject_type`)",
            "CREATE INDEX IF NOT EXISTS `idx_capability_grants_active` ON `capability_grants` (`revoked`, `expires_at_ms`)",
        ).forEach(db::execSQL)
    }

    private fun rebuildPortableMemoryFts(db: SQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_au")
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ad")
        db.execSQL("DROP TABLE IF EXISTS memory_fts")
        db.execSQL(MEMORY_FTS_PORTABLE_CREATE_SQL.trimIndent())
        db.execSQL(MEMORY_FTS_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    }

    private fun ensureColumns(
        db: SQLiteDatabase,
        table: String,
        additions: List<Pair<String, String>>,
    ) {
        val existing = db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet {
                if (nameIndex >= 0) while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        additions.forEach { (name, declaration) ->
            if (name !in existing) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $declaration")
        }
    }

    private fun readRoomIdentityHash(db: SQLiteDatabase): String? = runCatching {
        db.rawQuery(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun stampCurrentIdentity(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(EXPECTED_IDENTITY_HASH),
        )
    }

    private fun reconcileCurrentV35Delta(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            ensureColumns(
                db,
                "workspaces",
                listOf("storage_mode" to "TEXT NOT NULL DEFAULT 'PRIVATE'"),
            )
            ensureExecutionV35Schema(db)
            ensureCapabilityGrantsV35Schema(db)
            // authoritySubjectId belongs to MIGRATION_38_39; never add it to a v35 database.
            stampCurrentIdentity(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Call after a restore has written the database file, and only when the restore actually
     * included the database. Safe to call when the file is a genuine agent backup (every
     * statement is idempotent) or when the file does not exist (no-op).
     */
    fun reconcile(context: Context) {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            Log.i(TAG, "reconcile: no database file at ${dbFile.absolutePath}, skipping")
            return
        }
        try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                val version = db.version // PRAGMA user_version
                val identityHash = readRoomIdentityHash(db)
                when (reconcilePlan(version, identityHash)) {
                    ReconcilePlan.SKIP -> {
                        if (version > EXPECTED_VERSION) {
                            Log.w(
                                TAG,
                                "reconcile: backup db version $version is newer than " +
                                    "$EXPECTED_VERSION; leaving untouched",
                            )
                        } else {
                            Log.i(TAG, "reconcile: current schema already verified; skipping")
                        }
                        return
                    }
                    ReconcilePlan.CURRENT_V35_DELTA -> {
                        reconcileCurrentV35Delta(db)
                        Log.i(TAG, "reconcile: applied same-version v35 workspace-storage delta")
                        return
                    }
                    ReconcilePlan.FULL_COMPATIBILITY -> Unit
                }

                db.beginTransaction()
                try {
                    // `pending_chat_commands` was introduced by the v28→v29 migration. An
                    // earlier compatibility build could create its latest form before Room ran,
                    // which made every later ADD COLUMN migration collide with a future field.
                    // A database declaring <v29 cannot contain legitimate command-queue rows,
                    // so discard only that premature table and let Room create its historical
                    // v29 shape before applying its subsequent migrations.
                    dropPrematureForkTables(db, version)
                    if (version < 35) normalizeLegacyWorkspaces(db)
                    if (version < 18) dropPrematureScheduledJobsTable(db)
                    else normalizeLegacyScheduledJobs(db, version)
                    if (version < 29) dropPrematurePendingChatCommandsTable(db)

                    // A legacy database must be allowed to grow through Room's migration
                    // chain. In particular, the v28 scheduled_jobs definition contains
                    // targetConversationId; creating that latest table for a v1–v27 backup
                    // makes a later migration add the same column again.
                    FORK_ONLY_DDL
                        .asSequence()
                        // `scheduled_jobs` is fork-only in older upstream backups. Keep creating
                        // it when it is absent, but use the declared database-version shape:
                        // targetConversationId is a v28 column and must be added only by the
                        // registered 27→28 migration.
                        .map { ddl ->
                            when {
                                version < 18 && ddl.contains("CREATE TABLE IF NOT EXISTS `scheduled_jobs`") -> null
                                version < 19 && ddl.contains("CREATE TABLE IF NOT EXISTS `ssh_hosts`") -> null
                                version < 20 && ddl.contains("CREATE TABLE IF NOT EXISTS `telegram_chats`") -> null
                                version < 21 && ddl.contains("CREATE TABLE IF NOT EXISTS `scheduled_job_runs`") -> null
                                version < 22 && (ddl.contains("CREATE TABLE IF NOT EXISTS `workflows`") || ddl.contains("CREATE TABLE IF NOT EXISTS `workflow_runs`")) -> null
                                version < 24 && ddl.contains("CREATE TABLE IF NOT EXISTS `agent_runs`") -> null
                                version < 26 && ddl.contains("CREATE TABLE IF NOT EXISTS `workspaces`") -> null
                                version < 35 && ddl.contains("CREATE TABLE IF NOT EXISTS `workspaces`") ->
                                    ddl.replace(", `storage_mode` TEXT NOT NULL DEFAULT 'PRIVATE'", "")
                                version < 27 && ddl.contains("CREATE TABLE IF NOT EXISTS `alarms`") -> null
                                version < 29 && ddl.contains("CREATE TABLE IF NOT EXISTS `pending_chat_commands`") -> null
                                version < 31 && ddl.contains("CREATE TABLE IF NOT EXISTS `memory_") -> null
                                version < 32 && (ddl.contains("CREATE TABLE IF NOT EXISTS `memory_relation_candidates`") || ddl.contains("CREATE TABLE IF NOT EXISTS `memory_backfill_runs`")) -> null
                                version < 35 && (ddl.contains("CREATE TABLE IF NOT EXISTS `execution_records`") || ddl.contains("CREATE TABLE IF NOT EXISTS `capability_grants`")) -> null
                                version < 21 && ddl.contains("CREATE TABLE IF NOT EXISTS `scheduled_jobs`") ->
                                    ddl
                                        .replace(", `mode` TEXT NOT NULL DEFAULT 'llm'", "")
                                        .replace(", `actionsJson` TEXT", "")
                                        .replace(", `cronExpression` TEXT", "")
                                        .replace(", `timezone` TEXT", "")
                                        .replace(", `startAtUnixMs` INTEGER", "")
                                        .replace(", `endAtUnixMs` INTEGER", "")
                                        .replace(", `maxRuns` INTEGER", "")
                                        .replace(", `runsSoFar` INTEGER NOT NULL DEFAULT 0", "")
                                        .replace(", `catchup` TEXT NOT NULL DEFAULT 'fire_once'", "")
                                        .replace(", `description` TEXT", "")
                                        .replace(", `tags` TEXT", "")
                                        .replace(", `targetConversationId` TEXT", "")
                                version < 28 && ddl.contains("CREATE TABLE IF NOT EXISTS `scheduled_jobs`") ->
                                    ddl.replace(", `targetConversationId` TEXT", "")
                                version < 39 && ddl.contains("CREATE TABLE IF NOT EXISTS `pending_chat_commands`") ->
                                    ddl.replace(", `authoritySubjectId` TEXT", "")
                                else -> ddl
                            }
                        }
                        .filterNotNull()
                        .forEach(db::execSQL)
                    // `targetConversationId` first appears in schema v28. For an imported v27
                    // database, Room must perform 27 -> 28 itself; adding it here would make
                    // Room run the same ALTER TABLE twice and brick startup. Databases already
                    // at v28+ cannot revisit that migration, so they still need this repair.
                    if (version >= 28) ensureScheduledJobsV29Column(db)
                    if (version >= 29) ensureConversationFolderV29Column(db)

                    // A shared upstream user_version may already be ahead of the first agent
                    // memory migration. Add only the schema floors Room will no longer visit.
                    if (version >= 31) {
                        ensureMemoryV31Columns(db)
                        ensureMemoryV31CaptureColumns(db)
                    }
                    if (version >= 32) ensureMemoryV32Schema(db)
                    if (version >= 33) ensureMemoryV33Schema(db)
                    if (version >= 34) ensureBrowserV34Schema(db)
                    if (version >= 35) {
                        ensureColumns(
                            db,
                            "workspaces",
                            listOf("storage_mode" to "TEXT NOT NULL DEFAULT 'PRIVATE'"),
                        )
                        ensureExecutionV35Schema(db)
                        ensureCapabilityGrantsV35Schema(db)
                    }
                    if (version >= 36) {
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                                "ON `message_node` (`conversation_id`, `node_index`)",
                        )
                    }
                    if (version >= 37) ensureExecutionV37Schema(db)
                    if (version >= 38) ensurePetV38Schema(db)
                    if (version >= 39) ensurePendingCommandAuthorityV39Schema(db)
                    if (version >= 40) ensureToolExperienceV40Schema(db)
                    if (version >= 41) ensureToolShortcutV41Schema(db)

                    // Older backups must keep their original user_version so Room can run
                    // every real migration (including 28→29). Precreating fork-only tables
                    // makes upstream backups compatible, but stamping the current version here
                    // would silently skip migrations and risk losing schema changes. Only a
                    // database already at the current Room version receives the fork identity
                    // hash, because Room will not run a migration in that case.
                    if (version == EXPECTED_VERSION) {
                        stampCurrentIdentity(db)
                    } else {
                        Log.i(TAG, "reconcile: kept older user_version=$version for Room migrations")
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                Log.i(TAG, "reconcile: reconciled imported db (version=$version)")
            }
        } catch (t: Throwable) {
            // Never let reconciliation break the restore. Worst case is the pre-existing
            // behaviour (a crash on next open); the user's rows are still on disk.
            Log.w(TAG, "reconcile: failed to reconcile imported db", t)
        }
    }
}
