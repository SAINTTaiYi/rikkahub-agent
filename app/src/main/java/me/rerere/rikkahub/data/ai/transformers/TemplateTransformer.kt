package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.rikkahub.utils.toLocalTime
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal data class TemplateMessageClock(val time: String, val date: String)

internal fun templateMessageClock(createdAt: kotlinx.datetime.LocalDateTime): TemplateMessageClock {
    val timestamp = createdAt.toInstant(TimeZone.currentSystemDefault())
    val instant = java.time.Instant.ofEpochSecond(
        timestamp.epochSeconds,
        timestamp.nanosecondsOfSecond.toLong(),
    )
    return TemplateMessageClock(
        time = instant.toLocalTime(),
        date = instant.toLocalDate(),
    )
}

class TemplateTransformer(
    private val engine: PebbleEngine,
    private val settingsStore: SettingsStore
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = engine.getTemplate(ctx.assistant.id.toString())
        val timeZone = TimeZone.currentSystemDefault()
        return messages.map { message ->
            val messageClock = templateMessageClock(message.createdAt)
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            template.evaluate(
                                result, mapOf(
                                    "message" to part.text,
                                    "role" to message.role.name.lowercase(),
                                    "time" to messageClock.time,
                                    "date" to messageClock.date,
                                )
                            )
                            part.copy(
                                text = result.toString()
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        return StringReader(content)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
