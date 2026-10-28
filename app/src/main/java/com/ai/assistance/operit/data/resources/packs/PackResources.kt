package com.ai.assistance.operit.data.resources.packs

import android.content.Context
import com.ai.assistance.operit.data.resources.models.*
import com.ai.assistance.operit.data.storage.LocalModelRuntimeRegistry
import com.ai.assistance.operit.data.storage.LocalModelUsageHandle
import com.ai.assistance.operit.data.storage.LocalModelDeleteOutcome
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResourcePackSpec(
    val id: String, val name: String, val version: String, val files: List<ModelFile>,
    val archive: ModelFile, val loadOrder: List<String> = emptyList(),
    val extension: com.ai.assistance.operit.data.resources.extensions.BuiltInExtensionSpec? = null,
)

@Serializable
data class ResourcePackCatalog(val schemaVersion: Int, val releaseTag: String, val packs: List<ResourcePackSpec>)

/** 模板、逆向工具和语音运行库共用固定清单，下载源不影响这类独立发行资源。 */
object PackResources {
    const val OCR_MODELS = "ocr-google-mlkit"
    const val APK_REVERSE = "apk-reverse"
    const val SPEECH_ENGINE = "engine-sherpa-onnx"
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val stamps = ConcurrentHashMap<String, List<Pair<Long, Long>>>()
    @Volatile private var cachedCatalog: ResourcePackCatalog? = null
    private val bundledCache = ConcurrentHashMap<String, Boolean>()
    private val engineLock = Any()
    private var engineLoaded = false
    private var engineFailure: Throwable? = null
    private var engineUsage: LocalModelUsageHandle? = null

    fun catalog(context: Context): ResourcePackCatalog {
        cachedCatalog?.let { return it }
        return context.assets.open("offline-packs.json").bufferedReader().use { json.decodeFromString<ResourcePackCatalog>(it.readText()) }.also {
            require(it.schemaVersion == 1 && it.packs.map(ResourcePackSpec::id).distinct().size == it.packs.size)
            it.packs.forEach { pack ->
                storageSegment(pack.id); storageSegment(pack.version)
                require(pack.files.isNotEmpty() && pack.files.map(ModelFile::path).distinct().size == pack.files.size)
                pack.files.forEach { file -> validateRelativePath(file.path); require(file.bytes >= 0 && file.sha256.matches(Regex("[0-9a-f]{64}"))) }
                require(pack.loadOrder.all { name -> pack.files.any { file -> file.path == name } })
                pack.extension?.let { extension ->
                    require(extension.kind in setOf("plugin", "sandbox"))
                    validateRelativePath(extension.path)
                    require(pack.files.any { file -> file.path == extension.path })
                    require(extension.packageNames.isNotEmpty() && extension.packageNames.distinct().size == extension.packageNames.size)
                    require(extension.path.endsWith(if (extension.kind == "plugin") ".toolpkg" else ".js"))
                }
            }
            cachedCatalog = it
        }
    }

    fun spec(context: Context, id: String): ResourcePackSpec = catalog(context).packs.first { it.id == id }
    fun directory(context: Context, id: String): File {
        val pack = spec(context, id)
        val relative = if (id == SPEECH_ENGINE) "runtime/models/local/engines/sherpa-onnx/1.13.2/android-arm64-v8a/${pack.version}"
            else "runtime/resources/${pack.id}/${pack.version}"
        return File(context.filesDir, relative)
    }

    fun isBundled(context: Context, id: String): Boolean = bundledCache.getOrPut(id) {
        when {
            spec(context, id).extension != null -> {
                val path = spec(context, id).extension!!.path
                context.assets.list("packages").orEmpty().contains(File(path).name)
            }
            id == OCR_MODELS -> context.assets.list("mlkit-google-ocr-models").orEmpty().isNotEmpty()
            id.startsWith("template-") -> context.assets.list("templates/${id.removePrefix("template-")}").orEmpty().isNotEmpty()
            else -> false
        }
    }
    fun isReady(context: Context, id: String, force: Boolean = false): Boolean {
        if (isBundled(context, id)) return true
        val pack = spec(context, id)
        val root = directory(context, id)
        if (!File(root, "operit-resource.json").isFile) return false
        val stamp = pack.files.map { File(root, it.path).let { file -> file.length() to file.lastModified() } }
        if (!force && stamps[root.path] == stamp) return true
        val result = pack.files.all { ModelArchive.verified(root, it) }
        if (result) stamps[root.path] = stamp else stamps.remove(root.path)
        return result
    }

    fun requireDirectory(context: Context, id: String): File {
        check(isReady(context, id)) { "请先在设置 → 离线资源准备${spec(context, id).name}" }
        return directory(context, id)
    }

    fun resourceFile(context: Context, id: String, relative: String): File {
        val pack = spec(context, id)
        require(pack.files.any { it.path == relative }) { "资源路径不在清单内" }
        return requireDirectory(context, id).containedFile(relative)
    }

    suspend fun download(context: Context, id: String, phase: (ResourceDownloadPhase) -> Unit = {}, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        locks.getOrPut(id) { Mutex() }.withLock {
            check(!isBundled(context, id)) { "包内资源无需重复准备" }
            val pack = spec(context, id)
            if (isReady(context, id, force = true)) { progress(pack.archive.bytes, pack.archive.bytes); return@withLock }
            val cache = File(context.cacheDir, "offline-packs/${pack.archive.path}")
            if (cache.usableSpace < pack.archive.bytes + pack.files.sumOf(ModelFile::bytes) + 1024 * 1024 && context.cacheDir.usableSpace < pack.archive.bytes + pack.files.sumOf(ModelFile::bytes) + 1024 * 1024) error("设备空间不足")
            progress(0, pack.archive.bytes)
            phase(ResourceDownloadPhase.DOWNLOADING)
            ResourceTransfer.download(context, pack.archive.url, pack.archive, cache) { progress(it, pack.archive.bytes) }
            phase(ResourceDownloadPhase.INSTALLING)
            try {
                cache.inputStream().use { importLocked(context, id, it, pack.archive.path) { _, _ -> } }
                progress(pack.archive.bytes, pack.archive.bytes)
            } finally { cache.delete() }
        }
    }

    suspend fun importArchive(context: Context, id: String, input: InputStream, name: String, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(!isBundled(context, id)) { "包内资源无需导入" }
        locks.getOrPut(id) { Mutex() }.withLock { input.use { importLocked(context, id, it, name, progress) } }
    }

    private suspend fun importLocked(context: Context, id: String, input: InputStream, name: String, progress: (Long, Long) -> Unit) {
        val stage = File(context.filesDir, "runtime/staging/pack-${UUID.randomUUID()}")
        val job = currentCoroutineContext()
        try {
            ModelArchive.unpack(input, name, stage, { job.ensureActive() }) { progress(it, spec(context, id).files.sumOf(ModelFile::bytes)) }
            installExtracted(context, id, stage) { job.ensureActive() }
        } finally { stage.deleteRecursively() }
    }

    internal suspend fun restoreExtracted(context: Context, id: String, stage: File, checkpoint: () -> Unit) {
        locks.getOrPut(id) { Mutex() }.withLock {
            if (!isReady(context, id)) installExtracted(context, id, stage, checkpoint)
        }
    }
    internal fun validateExtracted(context: Context, id: String, stage: File, checkpoint: () -> Unit) {
        val pack = spec(context, id)
        val info = json.parseToJsonElement(File(stage, "operit-resource.json").readText()).let { it as kotlinx.serialization.json.JsonObject }
        require(info["id"]?.toString()?.trim('"') == id && info["version"]?.toString()?.trim('"') == pack.version) { "资源包版本与当前应用不匹配" }
        val expected = pack.files.map(ModelFile::path).toSet() + "operit-resource.json"
        require(stage.walkTopDown().filter(File::isFile).map { it.relativeTo(stage).invariantSeparatorsPath }.toSet() == expected) { "资源包文件清单不匹配" }
        require(pack.files.all { ModelArchive.verified(stage, it, checkpoint) }) { "资源包校验失败" }
    }
    internal fun installExtracted(context: Context, id: String, stage: File, checkpoint: () -> Unit) {
        validateExtracted(context, id, stage, checkpoint)
        val pack = spec(context, id)
        checkpoint()
        val destination = directory(context, id)
        pack.files.forEach { entry ->
            val file = stage.containedFile(entry.path)
            if (entry.executable) check(file.setExecutable(true, true))
            if (id == SPEECH_ENGINE) check(file.setReadOnly())
        }
        ModelArchive.install(stage, destination, checkpoint)
        stamps.remove(destination.path)
    }
    suspend fun exportArchive(context: Context, id: String, output: OutputStream, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        locks.getOrPut(id) { Mutex() }.withLock {
            check(!isBundled(context, id)) { "包内资源不提供备份入口" }
            val root = requireDirectory(context, id)
            val usage = LocalModelRuntimeRegistry.acquire(root) ?: error("资源正在移除")
            val job = currentCoroutineContext()
            try { output.use { ModelArchive.export(root, it, { job.ensureActive() }, progress) } } finally { usage.close() }
        }
    }

    suspend fun delete(context: Context, id: String, onProgress: ((Long, Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        locks.getOrPut(id) { Mutex() }.withLock {
            check(!isBundled(context, id)) { "不能移除包内资源" }
            check(LocalModelRuntimeRegistry.deleteIfUnused(directory(context, id), onProgress) == LocalModelDeleteOutcome.DELETED) { "资源正在使用或移除失败" }
            stamps.remove(directory(context, id).path)
        }
    }

    fun copyTemplate(context: Context, template: String, destination: File) {
        val id = "template-${storageSegment(template)}"
        if (isBundled(context, id)) {
            spec(context, id).files.forEach { entry ->
                val path = entry.path.split('/').joinToString("/") { if (it == "gitignore") ".gitignore" else it }
                val target = destination.containedFile(path)
                target.parentFile!!.mkdirs()
                context.assets.open("templates/$template/${entry.path.split('/').joinToString("/") { if (it == ".gitignore") "gitignore" else it }}").use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                if (entry.executable) check(target.setExecutable(true, true))
            }
            return
        }
        val root = requireDirectory(context, id)
        val usage = LocalModelRuntimeRegistry.acquire(root) ?: error("工程模板正在移除")
        try {
            spec(context, id).files.forEach { entry ->
                val path = entry.path.split('/').joinToString("/") { if (it == "gitignore") ".gitignore" else it }
                val target = destination.containedFile(path)
                target.parentFile!!.mkdirs()
                root.containedFile(entry.path).copyTo(target, overwrite = true)
                if (entry.executable) target.setExecutable(true, true)
            }
        } finally { usage.close() }
    }

    /** 只加载当前 APK 固定校验清单中的库，独立 SONAME 隔离宿主的 ONNX Runtime。 */
    fun loadSpeechEngine(context: Context) = synchronized(engineLock) {
        if (engineLoaded) return@synchronized
        engineFailure?.let { throw IllegalStateException("语音引擎加载失败，请重启应用后重试", it) }
        val pack = spec(context, SPEECH_ENGINE)
        val root = requireDirectory(context, SPEECH_ENGINE)
        require(pack.loadOrder.isNotEmpty())
        engineUsage = LocalModelRuntimeRegistry.acquire(root) ?: error("语音引擎正在移除")
        try {
            pack.loadOrder.forEach { System.load(root.containedFile(it).absolutePath) }
            engineLoaded = true
        } catch (failure: LinkageError) {
            engineFailure = failure
            throw IllegalStateException("无法加载本地语音引擎", failure)
        }
    }
}
