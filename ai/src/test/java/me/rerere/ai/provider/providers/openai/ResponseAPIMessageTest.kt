package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseAPIMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        return api.buildMessages(messages)
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, messages, params, stream)
    }

    private fun createReasoningParams(reasoningLevel: ReasoningLevel = ReasoningLevel.OFF): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should produce sequential function_call and output pairs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        // Verify each function_call is followed by its output (in pairs)
        val callIds = listOf("call_1", "call_2", "call_3")
        for (callId in callIds) {
            var callIndex = -1
            var outputIndex = -1
            for (i in result.indices) {
                val item = result[i].jsonObject
                if (item["type"]?.jsonPrimitive?.content == "function_call" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    callIndex = i
                }
                if (item["type"]?.jsonPrimitive?.content == "function_call_output" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    outputIndex = i
                }
            }
            assertTrue("Should find function_call for $callId", callIndex >= 0)
            assertTrue("Should find function_call_output for $callId", outputIndex >= 0)
            assertEquals("Output should immediately follow call for $callId",
                callIndex + 1, outputIndex)
        }
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `memory extraction omits Response API reasoning when disabled`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams().copy(
                omitReasoningConfigurationWhenOff = true,
            ),
        )

        assertFalse(requestBody.containsKey("reasoning"))
    }

    @Test
    fun `memory extraction prevents custom body from restoring Response API reasoning`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams().copy(
                omitReasoningConfigurationWhenOff = true,
                customBody = listOf(CustomBody("reasoning", JsonPrimitive("none"))),
            ),
        )

        assertFalse(requestBody.containsKey("reasoning"))
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `system prompt text should be serialized into Response API instructions`() {
        val prompt = "Assistant prompt\n\nTool guidance"
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(),
            messages = listOf(
                UIMessage.system(prompt),
                UIMessage.user("hello")
            ),
            params = createReasoningParams()
        )

        assertEquals(prompt, requestBody["instructions"]?.jsonPrimitive?.content)
    }

    @Test
    fun `response api should encode image input`() {
        val result = invokeBuildMessages(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("Describe this image"),
                        UIMessagePart.Image("data:image/png;base64,AA=="),
                    )
                )
            )
        )

        val content = result.single().jsonObject["content"]!!.jsonArray
        val image = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
        assertEquals(
            "data:image/png;base64,AA==",
            image.jsonObject["image_url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `historical screenshot is omitted from responses input for a text only model`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_image",
            toolName = "take_screenshot",
            input = "{}",
            output = listOf(UIMessagePart.Image("data:image/png;base64,AA==")),
        )

        val result = api.buildMessages(
            messages = listOf(
                UIMessage.user("inspect"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
                UIMessage.user("continue"),
            ),
            toolResultInputModalities = listOf(Modality.TEXT),
        ).toString()

        assertTrue(result.contains("Image omitted"))
        assertFalse(result.contains("data:image/png;base64,AA=="))
    }

    @Test
    fun `responses sends a tool image exactly once in the following multimodal item`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_image",
            toolName = "screenshot",
            input = "{}",
            output = listOf(
                UIMessagePart.Text("before"),
                UIMessagePart.Image("data:image/png;base64,AA=="),
                UIMessagePart.Text("after"),
            ),
        )

        val result = api.buildMessages(
            messages = listOf(
                UIMessage.user("inspect"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
            ),
            toolResultInputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val imageBlocks = result.flatMap { item ->
            (item.jsonObject["content"] as? JsonArray).orEmpty()
        }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
        val multimodal = result.first { item ->
            (item.jsonObject["content"] as? JsonArray)
                ?.any { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" } == true
        }.jsonObject["content"]!!.jsonArray

        assertEquals(1, imageBlocks.size)
        assertEquals(
            listOf("input_text", "input_image", "input_text"),
            multimodal.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
        assertEquals(1, result.toString().split("data:image/png;base64,AA==").size - 1)
    }

    @Test
    fun `response api should map all adjustable reasoning budgets`() {
        listOf(
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "xhigh",
        ).forEach { (level, expected) ->
            val requestBody = invokeBuildRequestBody(
                providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
                messages = emptyList(),
                params = createReasoningParams(reasoningLevel = level),
            )

            assertEquals(
                expected,
                requestBody["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `function and builtin tools coexist in one Response API array`() {
        val functionTool = Tool(
            name = "owner_tts_manage",
            description = "Owner tool",
            parameters = { InputSchema.Obj(JsonObject(emptyMap())) },
            execute = { emptyList() },
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            messages = emptyList(),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-test",
                    displayName = "gpt-test",
                    abilities = listOf(ModelAbility.TOOL),
                    tools = setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration),
                ),
                tools = listOf(functionTool),
            ),
        )

        val tools = requestBody["tools"]!!.jsonArray
        assertEquals(3, tools.size)
        assertTrue(tools.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function" })
        assertTrue(tools.any { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" })
        assertTrue(tools.any { it.jsonObject["type"]?.jsonPrimitive?.content == "image_generation" })
        assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "owner_tts_manage" })
    }

    @Test
    fun `Response API omits tools key only when both tool surfaces are empty`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(),
            messages = emptyList(),
            params = createReasoningParams(),
        )

        assertFalse(requestBody.containsKey("tools"))
    }

    @Test
    fun `response api content filter terminal is safety not recoverable incomplete`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp-filtered",
              "model": "test-model",
              "status": "incomplete",
              "incomplete_details": {"reason": "content_filter"},
              "output": [],
              "usage": {"input_tokens": 10, "output_tokens": 0, "total_tokens": 10}
            }
            """.trimIndent(),
        ).jsonObject

        val chunk = api.parseResponseOutput(response)

        assertEquals(FinishCategory.SAFETY, chunk.terminal?.category)
        assertEquals("content_filter", chunk.terminal?.providerReason)
    }

    @Test
    fun `response api refusal remains visible but terminal is safety`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp-refusal",
              "model": "test-model",
              "status": "completed",
              "output": [
                {
                  "type": "message",
                  "content": [
                    {"type": "refusal", "refusal": "I cannot help with that request."}
                  ]
                }
              ],
              "usage": {"input_tokens": 10, "output_tokens": 7, "total_tokens": 17}
            }
            """.trimIndent(),
        ).jsonObject

        val chunk = api.parseResponseOutput(response)
        val text = chunk.choices.single().message?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.singleOrNull()
            ?.text

        assertEquals(FinishCategory.SAFETY, chunk.terminal?.category)
        assertEquals("refusal", chunk.terminal?.providerReason)
        assertEquals("I cannot help with that request.", text)
    }

    // ==================== Helper Functions ====================

    private fun createToolParams(
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet()
    ): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.TOOL),
                tools = builtInTools
            ),
            tools = tools
        )
    }

    private fun createFunctionTool(name: String): Tool {
        return Tool(
            name = name,
            description = "test tool",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
