package me.rerere.rikkahub.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexCredentialStore
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MemoryFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.fts.ensureMemoryFtsSchema
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.db.migrations.MIGRATION_26_27
import me.rerere.rikkahub.data.db.migrations.MIGRATION_27_28
import me.rerere.rikkahub.data.db.migrations.MIGRATION_28_29
import me.rerere.rikkahub.data.db.migrations.MIGRATION_29_30
import me.rerere.rikkahub.data.db.migrations.MIGRATION_30_31
import me.rerere.rikkahub.data.db.migrations.MIGRATION_31_32
import me.rerere.rikkahub.data.db.migrations.MIGRATION_32_33
import me.rerere.rikkahub.data.db.migrations.MIGRATION_33_34
import me.rerere.rikkahub.data.db.migrations.MIGRATION_34_35
import me.rerere.rikkahub.data.db.migrations.MIGRATION_35_36
import me.rerere.rikkahub.data.db.migrations.MIGRATION_36_37
import me.rerere.rikkahub.data.db.migrations.MIGRATION_37_38
import me.rerere.rikkahub.data.db.migrations.MIGRATION_38_39
import me.rerere.rikkahub.data.db.migrations.MIGRATION_39_40
import me.rerere.rikkahub.data.db.migrations.MIGRATION_40_41
import me.rerere.rikkahub.data.db.migrations.MIGRATION_41_42
import me.rerere.rikkahub.data.repository.MemorySearchIndex
import me.rerere.rikkahub.data.repository.MemoryRetriever
import me.rerere.rikkahub.memory.AndroidMemoryWorkScheduler
import me.rerere.rikkahub.memory.DefaultMemoryV2Coordinator
import me.rerere.rikkahub.memory.MemoryCaptureStore
import me.rerere.rikkahub.memory.MemoryEmergencyGate
import me.rerere.rikkahub.memory.MemoryExtractor
import me.rerere.rikkahub.memory.MemoryProcessingStore
import me.rerere.rikkahub.memory.MemoryMetadataReconciler
import me.rerere.rikkahub.memory.DefaultMemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryV2Coordinator
import me.rerere.rikkahub.memory.MemoryWorkScheduler
import me.rerere.rikkahub.memory.ProviderMemoryExtractor
import me.rerere.rikkahub.memory.RoomMemoryCaptureStore
import me.rerere.rikkahub.memory.RoomMemoryProcessingStore
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetHandoffCoordinator
import me.rerere.rikkahub.pet.PetDiaryToolProvider
import me.rerere.rikkahub.pet.PetDialogueGenerator
import me.rerere.rikkahub.pet.PetPersonaSource
import me.rerere.rikkahub.pet.PetSummaryScheduler
import me.rerere.rikkahub.pet.AndroidPetSummaryScheduler
import me.rerere.rikkahub.pet.PetDiarySummarizer
import me.rerere.rikkahub.pet.PetHandoffRecovery
import me.rerere.rikkahub.pet.PetDiagnostics
import me.rerere.rikkahub.pet.behavior.PetActionTraceStore
import me.rerere.rikkahub.pet.behavior.PetRuntimeDiagnostics
import me.rerere.rikkahub.service.chat.DurableCommandQueue
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import org.koin.core.qualifier.named
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_23_24,
                MIGRATION_26_27,
                MIGRATION_27_28,
                MIGRATION_28_29,
                MIGRATION_29_30,
                MIGRATION_30_31,
                MIGRATION_31_32,
                MIGRATION_32_33,
                MIGRATION_33_34,
                MIGRATION_34_35,
                MIGRATION_35_36,
                MIGRATION_36_37,
                MIGRATION_37_38,
                MIGRATION_38_39,
                MIGRATION_39_40,
                MIGRATION_40_41,
                MIGRATION_41_42,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(me.rerere.rikkahub.data.db.fts.MESSAGE_FTS_CREATE_SQL.trimIndent())
                    ensureMemoryFtsSchema(db)
                }
            })
            .openHelperFactory(createAppSQLiteOpenHelperFactory(context))
            .build()
    }

    single { DurableCommandQueue(get<AppDatabase>().pendingChatCommandDao()) }
    single { me.rerere.rikkahub.owner.OwnerOperationBootRecovery(get<AppDatabase>().hostOperationDao()) }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().memoryV2Dao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        MessageFtsManager(get())
    }
    single<MemorySearchIndex> { MemoryFtsManager(get()) }
    single { MemoryRetriever(get()) }
    single { MemoryMetadataReconciler(get(), get()) }
    single<MemoryWorkScheduler> { AndroidMemoryWorkScheduler(get()) }
    single<MemoryCaptureStore> { RoomMemoryCaptureStore(get()) }
    single<MemoryProcessingStore> {
        RoomMemoryProcessingStore(
            database = get(),
            memoryDao = get(),
            memoryV2Dao = get(),
            retriever = get(),
            json = get(),
        )
    }
    single<MemoryMutationCoordinator> { DefaultMemoryMutationCoordinator(get()) }
    single<MemoryExtractor> { ProviderMemoryExtractor(get(), get()) }
    single<MemoryEmergencyGate> {
        val safetySettings = get<me.rerere.rikkahub.data.ai.AgentSafetySettings>()
        MemoryEmergencyGate { safetySettings.emergencyStopFlow.first() }
    }
    single<MemoryV2Coordinator> {
        DefaultMemoryV2Coordinator(
            captureStore = get(),
            workScheduler = get(),
            processingStore = get(),
            extractor = get(),
            emergencyGate = get(),
            idGenerator = { kotlin.uuid.Uuid.random().toString() },
        )
    }

    // Phase 24 — unified AgentRun ledger. DAO + the single shared writer/reader + the
    // boot-recovery sweep. AgentRunRepository has no cross-dependencies (only the DAO), so
    // there is no DI-cycle risk here.
    single { get<AppDatabase>().agentRunDao() }
    single { AgentRunRepository(get()) }
    single { AgentRunBootRecovery(context = get(), repository = get()) }

    // Authoritative per-execution ledger. This is intentionally distinct from AgentRun: one
    // AgentRun may own many runtime handles, each with its own cancellation/recovery outcome.
    single { get<AppDatabase>().executionRecordDao() }
    single { get<AppDatabase>().executionEventDao() }
    single { get<AppDatabase>().pendingToolApprovalDao() }
    single { get<AppDatabase>().pendingChatCommandDao() }
    single { get<AppDatabase>().petDialogueDao() }
    single<PetSummaryScheduler> { AndroidPetSummaryScheduler(context = get()) }
    single { PetDialogueRepository(database = get(), dao = get(), summaryScheduler = get()) }
    single { get<AppDatabase>().toolExperienceDao() }
    single { me.rerere.rikkahub.toolcatalog.ToolExperienceRepository(database = get(), dao = get()) }
    single { get<AppDatabase>().toolShortcutDao() }
    single { me.rerere.rikkahub.toolcatalog.ToolShortcutRepository(database = get(), dao = get()) }
    single {
        me.rerere.rikkahub.diagnostics.ToolCatalogDiagnostics(
            experiences = get(),
            shortcuts = get(),
        )
    }
    single {
        PetHandoffCoordinator(
            database = get(),
            dao = get(),
            pendingCommandDao = get(),
            dialogueRepository = get(),
            conversationRepository = get(),
            chatService = get(),
            authority = get(),
            appScope = get<AppScope>(),
        )
    }
    single { PetHandoffRecovery(dao = get(), coordinator = get()) }
    single { PetDiaryToolProvider(dao = get(), repository = get()) }
    single { PetPersonaSource(settingsStore = get()) }
    single { PetDialogueGenerator(settingsStore = get(), providerManager = get(), secretVault = get()) }
    single { PetDiarySummarizer(settingsStore = get(), providerManager = get()) }
    single { PetActionTraceStore() }
    single { PetRuntimeDiagnostics() }
    single {
        PetDiagnostics(
            context = get(),
            dao = get(),
            settingsStore = get(),
            summaryScheduler = get(),
            actionTraceStore = get(),
            runtimeDiagnosticsStore = get(),
        )
    }
    single { me.rerere.rikkahub.data.execution.ExecutionConsistencyMetrics() }
    single {
        me.rerere.rikkahub.data.execution.ExecutionStateTransaction(
            database = get(),
            recordDao = get(),
            eventDao = get(),
            metrics = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ExecutionRetentionManager(
            recordDao = get(),
            eventDao = get(),
            approvalDao = get(),
            scope = get<AppScope>(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ExecutionRepository(
            dao = get(),
            transaction = get(),
            retention = get(),
        )
    }
    single {
        me.rerere.rikkahub.toolcatalog.ToolExperienceRecorder(
            executionRepository = get(),
            experiences = get(),
            shortcuts = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ManagedExecutionRegistration(
            repository = get(),
            trackingHealth = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.SecondUserApprovalLifecycle(
            database = get(),
            conversationRepository = get(),
            approvalDao = get(),
            executionRepository = get(),
            retentionManager = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.SecondUserApprovalRecovery(
            settingsStore = get(),
            conversationRepository = get(),
            approvalDao = get(),
            lifecycle = get(),
            authorityService = get(),
        )
    }
    single { get<AppDatabase>().capabilityGrantDao() }
    single { me.rerere.rikkahub.data.capability.CapabilityGrantRepository(get()) }
    single<me.rerere.rikkahub.data.capability.CapabilityPolicyEngine> {
        me.rerere.rikkahub.data.capability.DefaultCapabilityPolicyEngine(
            grants = { get<me.rerere.rikkahub.data.capability.CapabilityGrantRepository>().current() },
        )
    }
    single<me.rerere.rikkahub.data.execution.ManagedExecutionVerifier> {
        me.rerere.rikkahub.data.execution.LiveManagedExecutionVerifier(
            ledgerVerifier = me.rerere.rikkahub.data.execution.LedgerManagedExecutionVerifier(get()),
            termuxSupervisor = get(),
            tokenProvider = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ExecutionBootRecovery(
            repository = get(),
            approvalDao = get(),
            reconciler = get(),
            cancellationCoordinator = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ManagedExecutionCallerResolver(
            ledger = get(),
            workspaceManager = get(),
        )
    }
    single<me.rerere.rikkahub.data.execution.ExecutionRuntimeProbe> {
        me.rerere.rikkahub.data.execution.DefaultExecutionRuntimeProbe(
            workspaceManager = get(),
            coordinator = get(),
            callerResolver = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ExecutionReconciler(
            repository = get(),
            probe = get(),
            metrics = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.ExecutionProbeScheduler(
            context = get(),
            scope = get<AppScope>(),
            repository = get(),
            reconciler = get(),
            workspaceManager = get(),
        )
    }
    single {
        me.rerere.rikkahub.data.execution.CancellationCoordinator(
            scope = get<AppScope>(),
            repository = get(),
            runtimeProbe = get(),
            callerResolver = get(),
            managedCoordinator = get(),
        )
    }
    single {
        me.rerere.rikkahub.diagnostics.ExecutionConsistencyDoctor(
            repository = get(),
            eventDao = get(),
            approvalDao = get(),
            conversationRepository = get(),
            workspaceManager = get(),
            reconciler = get(),
            approvalRecovery = get(),
            retentionManager = get(),
            trackingHealth = get(),
            metrics = get(),
        )
    }
    single<me.rerere.rikkahub.data.ai.execution.CriticalToolLifecycleSink> {
        me.rerere.rikkahub.data.execution.ExecutionRecordCriticalToolLifecycleSink(get())
    }
    single { me.rerere.rikkahub.data.ai.execution.ExecutionTrackingHealth() }

    // Alarm
    single { get<AppDatabase>().alarmDao() }
    single { AlarmRepository(get()) }
    single { AlarmScheduler(context = get(), repository = get()) }

    single {
        McpManager(
            context = get(),
            settingsStore = get(),
            appScope = get(),
            filesManager = get(),
            secretVault = get(),
        )
    }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            systemPromptBuilder = get(),
            toolExecutionGate = get(),
            toolRuntime = get(),
            toolStartableResolver = get(),
            toolExecutionBatchCoordinator = get(),
            contextBroker = get(),
            contextDiagnosticsStore = get(),
            secondUserSecretVault = get(),
            secretPlaintextSessions = get(),
            ephemeralToolResults = get(),
            runtimeSecretRedactor = get(),
            toolExperienceRecorder = get(),
        )
    }

    single { me.rerere.rikkahub.data.ai.SystemPromptBuilder() }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "RikkaHub-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .apply {
                // HEADERS-level logging prints Authorization: Bearer <api-key> to logcat.
                // Debug-only so release builds never leak provider keys to logcat.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    })
                }
            }
            .build().also { SearchService.init(it, get()) }
    }

    single<OkHttpClient>(named("codex")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        CodexAccountRepository(
            store = CodexCredentialStore(context = get(), json = get()),
            client = get(named("codex")),
            json = get(),
        )
    }

    single {
        CodexOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("codex")),
            repository = get(),
        )
    }

    single {
        val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = get()
        val codexRepository: CodexAccountRepository = get()
        val json: Json = get()
        ProviderManager(client = get(), context = get()).also { pm ->
            pm.registerProvider(
                "local_litert",
                me.rerere.locallm.litert.LiteRtProvider(
                    context = get(),
                    runtime = get(),
                    prefs = get(),
                    settingsUpdater = { transform ->
                        settingsStore.update { old -> old.copy(providers = transform(old.providers)) }
                    },
                ),
            )
            pm.registerProvider(
                "codex",
                CodexProvider(
                    context = get(),
                    client = get(named("codex")),
                    repository = codexRepository,
                    json = json,
                )
            )
        }
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get(),
            appDatabase = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get(),
            appDatabase = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
