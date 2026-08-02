package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val deletionPolicy: ConversationDeletionPolicy,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun listRecentConversationSummaries(
        excludeConversationId: Uuid,
        limit: Int = 20,
    ): List<LightConversationEntity> = conversationDAO.getRecentConversationSummaries(
        excludeConversationId = excludeConversationId.toString(),
        limit = limit.coerceIn(1, 50),
    )

    suspend fun getConversationSummaryById(conversationId: Uuid): LightConversationEntity? =
        conversationDAO.getConversationSummaryById(conversationId.toString())

    suspend fun getRecentNodeEntitiesBefore(
        conversationId: Uuid,
        beforeNodeIndex: Int?,
        limit: Int,
    ): List<MessageNodeEntity> = messageNodeDAO.getRecentNodesBefore(
        conversationId = conversationId.toString(),
        beforeNodeIndex = beforeNodeIndex,
        limit = limit.coerceIn(1, 64),
    )

    suspend fun getNodeEntitiesByIds(
        conversationId: Uuid,
        nodeIds: List<String>,
    ): List<MessageNodeEntity> = if (nodeIds.isEmpty()) emptyList() else
        messageNodeDAO.getNodesByIds(conversationId.toString(), nodeIds.distinct().take(50))

    suspend fun hasNodeBefore(conversationId: Uuid, beforeNodeIndex: Int): Boolean =
        messageNodeDAO.hasNodeBefore(conversationId.toString(), beforeNodeIndex)

    suspend fun searchMessagesInConversation(
        keyword: String,
        conversationId: Uuid,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 20,
        offset: Int = 0,
    ) = messageFtsManager.searchInConversation(
        keyword = keyword,
        conversationId = conversationId.toString(),
        sort = sort,
        limit = limit,
        offset = offset,
    )

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    /** Lightweight ownership lookup; never loads or deserializes message nodes. */
    suspend fun getAssistantIdOfConversation(uuid: Uuid): Uuid? =
        conversationDAO.getAssistantIdByConversationId(uuid.toString())
            ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            persistConversationInCurrentTransaction(conversation, insert = true)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation): ConversationUpdateResult {
        // Read the stored owner before writing. A caller can update title, prompt, pinned state,
        // and message graph on the protected second-user session, but no in-app route may move
        // that session to another assistant and thereby evade its deletion/authority guard.
        val stored = getConversationById(conversation.id)
            ?: return ConversationUpdateResult.Missing(conversation.id)
        if (stored.assistantId != conversation.assistantId && !deletionPolicy.canReassignAssistant(stored)) {
            return ConversationUpdateResult.RetainedSecondUser(stored.id)
        }
        database.withTransaction {
            persistConversationInCurrentTransaction(conversation, insert = false)
        }
        messageFtsManager.indexConversation(conversation)
        return ConversationUpdateResult.Updated(conversation.id)
    }

    /**
     * Persists the executable conversation graph inside an already-open Room transaction.
     *
     * Approval lifecycle code uses this narrow entry point so the message payload, redacted
     * approval projection, execution snapshot, and execution event either commit together or
     * all roll back. Search indexing is intentionally performed after that critical commit.
     */
    suspend fun persistConversationInCurrentTransaction(
        conversation: Conversation,
        insert: Boolean? = null,
    ) {
        check(database.inTransaction()) { "conversation_transaction_required" }
        val entity = conversationToConversationEntity(conversation)
        val shouldInsert = insert ?: !conversationDAO.existsById(conversation.id.toString())
        if (shouldInsert) {
            conversationDAO.insert(entity)
        } else {
            conversationDAO.update(entity)
            messageNodeDAO.deleteByConversation(conversation.id.toString())
        }
        saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
    }

    /** Refreshes the non-authoritative FTS projection after a critical transaction commits. */
    suspend fun refreshSearchProjection(conversation: Conversation) {
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversationTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    suspend fun updateConversationSuggestions(conversationId: Uuid, suggestions: List<String>) {
        conversationDAO.updateSuggestions(
            conversationId.toString(),
            JsonInstant.encodeToString(suggestions),
        )
    }

    suspend fun deleteConversation(conversation: Conversation): ConversationDeletionResult {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = getConversationById(conversation.id)
            ?: return ConversationDeletionResult.Missing(conversation.id)
        if (!deletionPolicy.canDelete(fullConversation)) {
            return ConversationDeletionResult.RetainedSecondUser(fullConversation.id)
        }
        messageFtsManager.deleteConversation(fullConversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(fullConversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
        return ConversationDeletionResult.Deleted(fullConversation.id)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    /**
     * Repair the FTS5 search index when SQLite reports a malformed inverted index. Drops
     * the message_fts virtual table (frees the corrupted index pages — DELETE alone won't),
     * recreates it via the shared schema, then re-indexes every conversation. Returns the
     * number of conversations re-indexed so the Doctor can report progress.
     */
    suspend fun repairAndRebuildIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Int {
        messageFtsManager.dropAndRecreate()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
        return total
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid): ConversationBatchDeletionResult {
        var deleted = 0
        val retained = mutableListOf<Uuid>()
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            when (deleteConversation(conversation)) {
                is ConversationDeletionResult.Deleted -> deleted++
                is ConversationDeletionResult.RetainedSecondUser -> retained += conversation.id
                is ConversationDeletionResult.Missing -> Unit
            }
        }
        return ConversationBatchDeletionResult(deleted, retained)
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId,
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        // Single atomic UPDATE — avoids the read→write TOCTOU that existed when
        // we read isPinned with getConversationById() and then flipped it.
        conversationDAO.togglePinStatus(conversationId.toString())
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        val entities = database.withTransaction {
            val rows = mutableListOf<MessageNodeEntity>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                rows += page
                offset += page.size
            }
            rows
        }
        // JSON decoding is CPU work and must not hold Room's transaction executor. Long agent
        // conversations can contain hundreds of nodes; keeping the transaction open here made
        // unrelated Paging loads appear empty while the system-assistant overlay was opening.
        return withContext(Dispatchers.Default) {
            entities.map { entity ->
                // Long agent histories can contain hundreds of JSON blobs. Keep this CPU work
                // off AppScope's main dispatcher and honour an overlay close between nodes.
                ensureActive()
                val nodeId = Uuid.parse(entity.id)
                MessageNode(
                    id = nodeId,
                    messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages),
                    selectIndex = entity.selectIndex,
                    isFavorite = favoriteNodeIds.contains(nodeId),
                )
            }
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
