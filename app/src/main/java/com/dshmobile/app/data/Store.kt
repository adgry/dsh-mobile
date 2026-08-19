package com.dshmobile.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.dshmobile.app.util.TokenEstimate
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Conversations are one JSON file each under `filesDir/conversations/`, with attached image bytes
 * kept beside them in `filesDir/attachments/`.
 *
 * The in-memory map is the source of truth while the app is alive. Persisting is asynchronous and
 * coalesced: a streaming reply rewrites its conversation object on every chunk, and only the last
 * state within each ~350 ms window reaches the disk.
 */
class ConversationStore(root: File, private val scope: CoroutineScope) {

    private val convDir = File(root, "conversations")
    private val attachDir = File(root, "attachments")

    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val byId = LinkedHashMap<String, Conversation>()
    private val dirty = Channel<String>(Channel.UNLIMITED)

    init {
        convDir.mkdirs()
        attachDir.mkdirs()
        scope.launch(Dispatchers.IO) {
            loadAll()
            _loaded.value = true
        }
        scope.launch(Dispatchers.IO) { writeLoop() }
    }

    // ---------------------------------------------------------------- reads

    fun get(id: String): Conversation? = byId[id]

    // --------------------------------------------------------------- writes

    fun put(conversation: Conversation, touch: Boolean = true) {
        val next = if (touch) conversation.copy(updatedAt = System.currentTimeMillis()) else conversation
        byId[next.id] = next
        publish()
        dirty.trySend(next.id)
    }

    fun delete(id: String) {
        val removed = byId.remove(id)
        publish()
        val files = removed?.messages?.flatMap { it.attachments }.orEmpty()
        scope.launch(Dispatchers.IO) {
            File(convDir, "$id.json").delete()
            files.forEach { File(attachDir, it.fileName).delete() }
        }
    }

    fun deleteAll() {
        byId.clear()
        publish()
        scope.launch(Dispatchers.IO) {
            convDir.listFiles()?.forEach { it.delete() }
            attachDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Force the pending state of one conversation to disk; used when a reply finishes. */
    suspend fun flush(id: String) = withContext(Dispatchers.IO) {
        byId[id]?.let { runCatching { writeOne(it) } }
        Unit
    }

    // ----------------------------------------------------------- attachments

    fun attachmentFile(attachment: Attachment): File = File(attachDir, attachment.fileName)

    suspend fun writeImage(
        bytes: ByteArray,
        mimeType: String,
        displayName: String,
        width: Int,
        height: Int,
    ): Attachment = withContext(Dispatchers.IO) {
        val id = newId()
        val ext = when {
            mimeType.contains("png", true) -> "png"
            mimeType.contains("webp", true) -> "webp"
            mimeType.contains("gif", true) -> "gif"
            else -> "jpg"
        }
        val name = "$id.$ext"
        File(attachDir, name).writeBytes(bytes)
        Attachment(
            id = id,
            kind = AttachmentKind.IMAGE,
            fileName = name,
            mimeType = mimeType,
            displayName = displayName,
            byteCount = bytes.size.toLong(),
            width = width,
            height = height,
            tokenEstimate = TokenEstimate.IMAGE_TOKENS,
        )
    }

    /** Stores extracted document text; the estimate is computed once, here. */
    suspend fun writeDocument(
        text: String,
        displayName: String,
        mimeType: String,
        byteCount: Long,
        truncated: Boolean,
    ): Attachment = withContext(Dispatchers.IO) {
        val id = newId()
        val name = "$id.txt"
        File(attachDir, name).writeText(text)
        Attachment(
            id = id,
            kind = AttachmentKind.DOCUMENT,
            fileName = name,
            mimeType = mimeType,
            displayName = displayName,
            byteCount = byteCount,
            charCount = text.length,
            truncated = truncated,
            tokenEstimate = TokenEstimate.ofText(text),
        )
    }

    suspend fun readDocumentText(attachment: Attachment): String? = withContext(Dispatchers.IO) {
        runCatching { attachmentFile(attachment).readText() }.getOrNull()
    }

    suspend fun readAttachment(attachment: Attachment): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { attachmentFile(attachment).readBytes() }.getOrNull()
    }

    /** Drops attachment files that no surviving message references. */
    suspend fun pruneAttachments() = withContext(Dispatchers.IO) {
        val referenced = byId.values
            .flatMap { it.messages }
            .flatMap { it.attachments }
            .mapTo(mutableSetOf()) { it.fileName }
        attachDir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
        Unit
    }

    // ------------------------------------------------------------- internals

    private fun publish() {
        _items.value = byId.values.sortedWith(
            compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt },
        )
    }

    private fun loadAll() {
        val files = convDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        val loadedItems = files.mapNotNull { f ->
            runCatching { AppJson.decodeFromString<Conversation>(f.readText()) }.getOrNull()
        }
        byId.clear()
        loadedItems.forEach { byId[it.id] = it }
        publish()
    }

    private suspend fun writeLoop() {
        val batch = mutableSetOf<String>()
        while (true) {
            batch += dirty.receive()
            delay(350)
            while (true) {
                val next = dirty.tryReceive().getOrNull() ?: break
                batch += next
            }
            batch.forEach { id -> byId[id]?.let { runCatching { writeOne(it) } } }
            batch.clear()
        }
    }

    private fun writeOne(conversation: Conversation) {
        convDir.mkdirs()
        val target = File(convDir, "${conversation.id}.json")
        val tmp = File(convDir, "${conversation.id}.json.tmp")
        tmp.writeText(AppJson.encodeToString(conversation))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
