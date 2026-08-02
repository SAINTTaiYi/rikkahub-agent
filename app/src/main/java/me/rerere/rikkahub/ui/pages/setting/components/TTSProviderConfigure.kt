package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.GenericHttpHeader

@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        // Provider type selector
        var expanded by remember { mutableStateOf(false) }
        val providers = remember { TTSProviderSetting.Types }

        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_provider_type)) },
            description = { Text(stringResource(R.string.setting_tts_page_provider_type_description)) },
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = when (setting) {
                        is TTSProviderSetting.OpenAI -> "OpenAI"
                        is TTSProviderSetting.Gemini -> "Gemini"
                        is TTSProviderSetting.SystemTTS -> "System TTS"
                        is TTSProviderSetting.MiniMax -> "MiniMax"
                        is TTSProviderSetting.Aura -> "Aura"
                        is TTSProviderSetting.Qwen -> "Qwen"
                        is TTSProviderSetting.Groq -> "Groq"
                        is TTSProviderSetting.XAI -> "xAI"
                        is TTSProviderSetting.MiMo -> "MiMo"
                        is TTSProviderSetting.GenericHttp -> stringResource(R.string.setting_tts_page_provider_generic_http)
                    },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    providers.forEach { providerClass ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (providerClass) {
                                        TTSProviderSetting.OpenAI::class -> "OpenAI"
                                        TTSProviderSetting.Gemini::class -> "Gemini"
                                        TTSProviderSetting.SystemTTS::class -> "System TTS"
                                        TTSProviderSetting.MiniMax::class -> "MiniMax"
                                        TTSProviderSetting.Aura::class -> "Aura"
                                        TTSProviderSetting.Qwen::class -> "Qwen"
                                        TTSProviderSetting.Groq::class -> "Groq"
                                        TTSProviderSetting.XAI::class -> "xAI"
                                        TTSProviderSetting.MiMo::class -> "MiMo"
                                        TTSProviderSetting.GenericHttp::class -> "Generic HTTP"
                                        else -> providerClass.simpleName ?: "Unknown"
                                    }
                                )
                            },
                            onClick = {
                                expanded = false
                                val newSetting = when (providerClass) {
                                    TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(
                                        id = setting.id,
                                        name = "OpenAI TTS"
                                    )

                                    TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(
                                        id = setting.id,
                                        name = "Gemini TTS"
                                    )

                                    TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(
                                        id = setting.id,
                                        name = "System TTS"
                                    )

                                    TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(
                                        id = setting.id,
                                        name = "MiniMax TTS"
                                    )

                                    TTSProviderSetting.Aura::class -> TTSProviderSetting.Aura(
                                        id = setting.id,
                                        name = "Aura TTS"
                                    )

                                    TTSProviderSetting.Qwen::class -> TTSProviderSetting.Qwen(
                                        id = setting.id,
                                        name = "Qwen TTS"
                                    )

                                    TTSProviderSetting.Groq::class -> TTSProviderSetting.Groq(
                                        id = setting.id,
                                        name = "Groq TTS"
                                    )

                                    TTSProviderSetting.XAI::class -> TTSProviderSetting.XAI(
                                        id = setting.id,
                                        name = "xAI TTS"
                                    )

                                    TTSProviderSetting.MiMo::class -> TTSProviderSetting.MiMo(
                                        id = setting.id,
                                        name = "MiMo TTS"
                                    )

                                    TTSProviderSetting.GenericHttp::class -> TTSProviderSetting.GenericHttp(
                                        id = setting.id,
                                        name = "Generic HTTP TTS",
                                    )

                                    else -> setting
                                }
                                onValueChange(newSetting)
                            }
                        )
                    }
                }
            }
        }

        // Name
        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_name)) },
            description = { Text(stringResource(R.string.setting_tts_page_name_description)) }
        ) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { newName ->
                    onValueChange(setting.copyProvider(name = newName))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_tts_page_name_placeholder)) }
            )
        }

        // Provider-specific fields
        when (setting) {
            is TTSProviderSetting.OpenAI -> OpenAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Gemini -> GeminiTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiniMax -> MiniMaxTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Aura -> AuraTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.SystemTTS -> SystemTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Qwen -> QwenTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Groq -> GroqTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.XAI -> XAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiMo -> MiMoTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.GenericHttp -> GenericHttpTTSConfiguration(setting, onValueChange)
        }
    }
}

@Composable
private fun GenericHttpTTSConfiguration(
    setting: TTSProviderSetting.GenericHttp,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    FormItem(label = { Text(stringResource(R.string.setting_tts_generic_endpoint)) }) {
        OutlinedTextField(
            value = setting.endpoint,
            onValueChange = { onValueChange(setting.copy(endpoint = it.take(4096))) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://example.com/tts") },
        )
    }
    GenericEnumField(
        label = stringResource(R.string.setting_tts_generic_method),
        value = setting.method,
        values = me.rerere.tts.provider.GenericHttpMethod.entries,
        onChange = { onValueChange(setting.copy(method = it)) },
    )
    GenericEnumField(
        label = stringResource(R.string.setting_tts_generic_body_encoding),
        value = setting.bodyEncoding,
        values = me.rerere.tts.provider.GenericHttpBodyEncoding.entries,
        onChange = { onValueChange(setting.copy(bodyEncoding = it)) },
    )
    FormItem(label = { Text(stringResource(R.string.setting_tts_generic_body_template)) }) {
        OutlinedTextField(
            value = setting.bodyTemplate,
            onValueChange = { onValueChange(setting.copy(bodyTemplate = it.take(32 * 1024))) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
    }
    FormItem(label = { Text(stringResource(R.string.setting_tts_generic_headers)) }) {
        OutlinedTextField(
            value = setting.headers.joinToString("\n") { "${it.name}: ${it.valueTemplate}" },
            onValueChange = { text ->
                val headers = text.lineSequence().mapNotNull { line ->
                    val split = line.split(':', limit = 2)
                    if (split.size != 2 || split[0].isBlank()) null else {
                        GenericHttpHeader(split[0].trim().take(128), split[1].trim().take(4096))
                    }
                }.take(32).toList()
                onValueChange(setting.copy(headers = headers))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            supportingText = { Text(stringResource(R.string.setting_tts_generic_secret_hint)) },
        )
    }
    GenericEnumField(
        label = stringResource(R.string.setting_tts_generic_response_mode),
        value = setting.responseMode,
        values = me.rerere.tts.provider.GenericHttpResponseMode.entries,
        onChange = { onValueChange(setting.copy(responseMode = it)) },
    )
    FormItem(label = { Text(stringResource(R.string.setting_tts_generic_response_path)) }) {
        OutlinedTextField(
            value = setting.responseJsonPath,
            onValueChange = { onValueChange(setting.copy(responseJsonPath = it.take(512))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    GenericEnumField(
        label = stringResource(R.string.setting_tts_generic_audio_format),
        value = setting.audioFormat,
        values = me.rerere.tts.model.AudioFormat.entries,
        onChange = { onValueChange(setting.copy(audioFormat = it)) },
    )
    FormItem(label = { Text(stringResource(R.string.setting_tts_generic_allow_private)) }) {
        androidx.compose.material3.Switch(
            checked = setting.allowPrivateNetwork,
            onCheckedChange = { onValueChange(setting.copy(allowPrivateNetwork = it)) },
        )
    }
    FormItem(label = { Text(stringResource(R.string.setting_tts_generic_max_bytes)) }) {
        OutlinedTextField(
            value = setting.maxResponseBytes.toString(),
            onValueChange = { raw ->
                raw.toIntOrNull()?.coerceIn(1, 64 * 1024 * 1024)?.let { bytes ->
                    onValueChange(setting.copy(maxResponseBytes = bytes))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun <T : Enum<T>> GenericEnumField(
    label: String,
    value: T,
    values: List<T>,
    onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FormItem(label = { Text(label) }) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = value.name,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.name) },
                        onClick = { expanded = false; onChange(candidate) },
                    )
                }
            }
        }
    }
}

/** Editable text plus a regular popup menu; unlike ExposedDropdownMenu this stays stable with IMEs. */
@Composable
private fun EditableDropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    options: List<Pair<String, String>>,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onValueChange(optionValue)
                    },
                )
            }
        }
    }
}

@Composable
private fun OpenAITTSConfiguration(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    // Voice
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        EditableDropdownField(
            value = setting.voice,
            onValueChange = { onValueChange(setting.copy(voice = it)) },
            options = voices.map { it to it },
        )
    }
}

@Composable
private fun MiMoTTSConfiguration(
    setting: TTSProviderSetting.MiMo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // MiMo 配置均为自由输入 默认值只是占位
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2-tts") }
        )
    }

    // Voice
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voice,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo_default") }
        )
    }
}

@Composable
private fun AuraTTSConfiguration(
    setting: TTSProviderSetting.Aura,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk_xxx") },
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://tts.aurastd.com/api/v1") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.8-turbo") }
        )
    }

    val voiceIds = listOf(
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig",
        "English_expressive_narrator"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        EditableDropdownField(
            value = setting.voiceId,
            onValueChange = { onValueChange(setting.copy(voiceId = it)) },
            options = voiceIds.map { it to it },
        )
    }

    val emotions = listOf(
        "neutral",
        "calm",
        "happy",
        "sad",
        "angry",
        "fearful",
        "disgusted",
        "surprised",
        "fluent",
        "whisper"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_emotion)) },
        description = { Text(stringResource(R.string.setting_tts_page_emotion_description)) }
    ) {
        EditableDropdownField(
            value = setting.emotion,
            onValueChange = { onValueChange(setting.copy(emotion = it)) },
            options = emotions.map { it to it },
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun MiniMaxTTSConfiguration(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.5-hd-preview") }
        )
    }

    // Voice ID
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        EditableDropdownField(
            value = setting.voiceId,
            onValueChange = { onValueChange(setting.copy(voiceId = it)) },
            options = voiceIds.map { it to it },
        )
    }

    // Emotion
    val emotions = listOf("calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_emotion)) },
        description = { Text(stringResource(R.string.setting_tts_page_emotion_description)) }
    ) {
        EditableDropdownField(
            value = setting.emotion,
            onValueChange = { onValueChange(setting.copy(emotion = it)) },
            options = emotions.map { it to it },
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun GeminiTTSConfiguration(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_gemini)) },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_gemini)) }
        )
    }

    // Voice Name
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceName,
            onValueChange = { newVoiceName ->
                onValueChange(setting.copy(voiceName = newVoiceName))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_voice_name_placeholder)) }
        )
    }
}

@Composable
private fun SystemTTSConfiguration(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // Speech Rate
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        description = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..3.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    // Pitch
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        description = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..2.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

@Composable
private fun QwenTTSConfiguration(
    setting: TTSProviderSetting.Qwen,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-tts-flash") }
        )
    }

    // Voice
    val voices = listOf(
        "Cherry", "Serene", "Ethan", "Chelsie",
        "Momo", "Vivian", "Moon", "Maia", "Kai",
        "Nofish", "Bella", "Jennifer", "Ryan",
        "Katerina", "Aiden", "Eldric Sage", "Mia",
        "Mochi", "Bellona", "Vincent", "Bunny",
        "Neil", "Elias", "Arthur", "Nini"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        EditableDropdownField(
            value = setting.voice,
            onValueChange = { onValueChange(setting.copy(voice = it)) },
            options = voices.map { it to it },
        )
    }

    // Language Type
    val languageTypes = listOf("Auto", "Chinese", "English", "Japanese", "Korean")

    FormItem(
        label = { Text("Language Type") },
        description = { Text("Language type for TTS synthesis") }
    ) {
        EditableDropdownField(
            value = setting.languageType,
            onValueChange = { onValueChange(setting.copy(languageType = it)) },
            options = languageTypes.map { it to it },
        )
    }
}

@Composable
private fun GroqTTSConfiguration(
    setting: TTSProviderSetting.Groq,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gsk_xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
    }

    // Voice
    val voices = listOf("austin", "natalie", "kailin")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        EditableDropdownField(
            value = setting.voice,
            onValueChange = { onValueChange(setting.copy(voice = it)) },
            options = voices.map { it to it },
        )
    }
}

@Composable
private fun XAITTSConfiguration(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("xai-xxx") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") }
        )
    }

    // Voice ID
    val voices = listOf(
        "eve" to "Eve",
        "ara" to "Ara",
        "rex" to "Rex",
        "sal" to "Sal",
        "leo" to "Leo"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        EditableDropdownField(
            value = setting.voiceId,
            onValueChange = { onValueChange(setting.copy(voiceId = it)) },
            options = voices,
        )
    }

    // Language
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es-ES" to "Spanish (Spain)",
        "es-MX" to "Spanish (Mexico)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "it" to "Italian",
        "ru" to "Russian",
        "ar-EG" to "Arabic (Egypt)",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "bn" to "Bengali"
    )

    FormItem(
        label = { Text("Language") },
    ) {
        EditableDropdownField(
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            options = languages.map { (code, label) -> code to "$label ($code)" },
        )
    }
}
