package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsAPIUsageTest {
    @Test
    fun `deepseek top level cache fields are parsed`() {
        val usage = parseChatCompletionsTokenUsage(
            Json.parseToJsonElement(
                """{"prompt_cache_hit_tokens":606848,"prompt_cache_miss_tokens":9200,"completion_tokens":42}""",
            ).jsonObject,
        )!!

        assertEquals(616_048, usage.promptTokens)
        assertEquals(606_848, usage.cachedTokens)
        assertEquals(42, usage.completionTokens)
        assertEquals(616_090, usage.totalTokens)
    }

    @Test
    fun `openai nested cached tokens remain supported`() {
        val usage = parseChatCompletionsTokenUsage(
            Json.parseToJsonElement(
                """{"prompt_tokens":695638,"completion_tokens":362,"total_tokens":696000,"prompt_tokens_details":{"cached_tokens":9088}}""",
            ).jsonObject,
        )!!

        assertEquals(695_638, usage.promptTokens)
        assertEquals(9_088, usage.cachedTokens)
        assertEquals(696_000, usage.totalTokens)
    }

    @Test
    fun `moonshot top level cached tokens are parsed`() {
        val usage = parseChatCompletionsTokenUsage(
            Json.parseToJsonElement(
                """{"prompt_tokens":17,"completion_tokens":3,"total_tokens":20,"cached_tokens":11}""",
            ).jsonObject,
        )!!

        assertEquals(11, usage.cachedTokens)
        assertEquals(17, usage.promptTokens)
        assertEquals(20, usage.totalTokens)
    }

    @Test
    fun `largest valid cache field wins across compatible response shapes`() {
        val usage = parseChatCompletionsTokenUsage(
            Json.parseToJsonElement(
                """{"prompt_tokens":700000,"prompt_cache_hit_tokens":650000,"prompt_tokens_details":{"cached_tokens":50000},"cache_read_input_tokens":640000}""",
            ).jsonObject,
        )!!

        assertEquals(650_000, usage.cachedTokens)
        assertEquals(700_000, usage.promptTokens)
    }
}
