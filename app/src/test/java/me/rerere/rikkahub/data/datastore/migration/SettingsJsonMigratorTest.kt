package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMigratorTest {
    @Test
    fun `legacy backup web search switch is copied to assistants without a value`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"enableWebSearch":true,"assistants":[{"id":"legacy"}]}""",
        )
        val assistant = JsonInstant.parseToJsonElement(migrated)
            .jsonObject
            .getValue("assistants")
            .jsonArray
            .single()
            .jsonObject

        assertTrue(assistant.getValue("enableWebSearch").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `explicit per assistant web search value wins during backup import`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"enableWebSearch":true,"assistants":[{"id":"current","enableWebSearch":false}]}""",
        )
        val assistant = JsonInstant.parseToJsonElement(migrated)
            .jsonObject
            .getValue("assistants")
            .jsonArray
            .single()
            .jsonObject

        assertFalse(assistant.getValue("enableWebSearch").jsonPrimitive.content.toBoolean())
    }
    @Test
    fun `legacy Grok provider restores as an xAI OpenAI compatible provider`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"providers":[{"type":"grok","id":"8f3e1d20-0000-4000-8000-000000000001","enabled":true,"name":"Grok","models":[],"balanceOption":{}}]}"""
        )

        val providerJson = JsonInstant.parseToJsonElement(migrated)
            .jsonObject
            .getValue("providers")
            .jsonArray
            .toString()
        val provider = JsonInstant.decodeFromString<List<ProviderSetting>>(providerJson).single()
            as ProviderSetting.OpenAI

        assertEquals("Grok", provider.name)
        assertEquals("https://api.x.ai/v1", provider.baseUrl)
    }

    @Test
    fun `unsupported legacy provider is dropped without affecting other providers`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"providers":[{"type":"obsolete","id":"8f3e1d20-0000-4000-8000-000000000002"},{"type":"openai","id":"8f3e1d20-0000-4000-8000-000000000003"}]}"""
        )

        val providers = JsonInstant.parseToJsonElement(migrated)
            .jsonObject
            .getValue("providers")
            .jsonArray

        assertEquals(1, providers.size)
        assertEquals("openai", providers.single().jsonObject.getValue("type").jsonPrimitive.content)
    }

}
