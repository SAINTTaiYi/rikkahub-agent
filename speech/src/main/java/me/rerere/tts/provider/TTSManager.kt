package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.providers.AuraTTSProvider
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.GroqTTSProvider
import me.rerere.tts.provider.providers.MiMoTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.QwenTTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider
import me.rerere.tts.provider.providers.XAITTSProvider
import me.rerere.tts.provider.providers.GenericHttpTTSProvider

class TTSManager(private val context: Context) {
    private val openAIProvider = OpenAITTSProvider()
    private val geminiProvider = GeminiTTSProvider()
    private val systemProvider = SystemTTSProvider()
    private val miniMaxProvider = MiniMaxTTSProvider()
    private val auraProvider = AuraTTSProvider()
    private val qwenProvider = QwenTTSProvider()
    private val groqProvider = GroqTTSProvider()
    private val xaiProvider = XAITTSProvider()
    private val miMoProvider = MiMoTTSProvider()
    private val genericHttpProvider = GenericHttpTTSProvider()
    private val registry = TTSProviderFactoryRegistry(
        listOf(
            TTSProviderFactory { context, setting, request ->
                when (setting) {
                    is TTSProviderSetting.OpenAI -> openAIProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.Gemini -> geminiProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.SystemTTS -> systemProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.MiniMax -> miniMaxProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.Aura -> auraProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.Qwen -> qwenProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.Groq -> groqProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.XAI -> xaiProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.MiMo -> miMoProvider.generateSpeech(context, setting, request)
                    is TTSProviderSetting.GenericHttp -> genericHttpProvider.generateSpeech(context, setting, request)
                }
            },
        ),
    )

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        request: TTSRequest
    ): Flow<AudioChunk> {
        return registry.generate(context, providerSetting, request)
    }
}
