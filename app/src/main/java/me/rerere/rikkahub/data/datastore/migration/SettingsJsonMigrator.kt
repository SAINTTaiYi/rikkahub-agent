package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [me.rerere.rikkahub.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as? JsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            // V4 stored one global web-search switch. Only assistants without an explicit
            // value inherit it, so newer per-assistant choices remain authoritative.
            root["assistants"]?.let { element ->
                val legacyEnabled = (root["enableWebSearch"] as? JsonPrimitive)
                    ?.booleanOrNull == true
                val migrated = migrateAssistantsWebSearch(
                    assistantsJson = JsonInstant.encodeToString(element),
                    legacyEnabled = legacyEnabled,
                )
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V5: Grok used to have its own ProviderSetting discriminator. It is now
            // OpenAI-compatible, so old backups restore it as an OpenAI provider while
            // retaining the provider ID, name, models and any user-supplied endpoint.
            // Drop an unrecognized provider entry instead of making one obsolete provider
            // prevent the rest of a settings backup from being restored.
            root["providers"]?.let { element ->
                val providers = element as? JsonArray ?: return@let
                val supportedProviderTypes = setOf(
                    "openai", "google", "claude", "aicore", "local_litert", "codex"
                )
                root["providers"] = JsonArray(
                    providers.mapNotNull { providerElement ->
                        val provider = providerElement as? JsonObject ?: run {
                            Log.w(TAG, "migrate: Dropping malformed provider entry from backup")
                            return@mapNotNull null
                        }
                        when (val type = provider["type"]?.toString()?.trim('"')) {
                            "grok" -> JsonObject(provider.toMutableMap().apply {
                                put("type", JsonPrimitive("openai"))
                                if ("baseUrl" !in this) {
                                    put("baseUrl", JsonPrimitive("https://api.x.ai/v1"))
                                }
                            })
                            in supportedProviderTypes -> provider
                            else -> {
                                Log.w(TAG, "migrate: Dropping unsupported provider type: $type")
                                null
                            }
                        }
                    }
                )
            }

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }
}
