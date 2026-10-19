package com.sandev.starfalldungeon

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream

/**
 * Local-only Gemma controller. Inference runs off the UI/game loop; only whitelisted
 * high-level commands are returned to the game engine.
 */
internal class GemmaLocalAi(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onChatReply: (String) -> Unit,
    private val onTactic: (String, String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: InferenceEngine
    private var engineReady = false
    private var modelReady = false
    private var busy = false
    private var activeModel: File? = null
    private var tacticJob: Job? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val prefs = context.getSharedPreferences("starfall_gemma_ai", Context.MODE_PRIVATE)
    private val modelDir = File(context.filesDir, "models")

    fun initialize() {
        scope.launch(Dispatchers.IO) {
            try {
                engine = AiChat.getInferenceEngine(context.applicationContext)
                val state = withTimeout(45_000L) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized ||
                            it is InferenceEngine.State.ModelReady ||
                            it is InferenceEngine.State.Error
                    }
                }
                if (state is InferenceEngine.State.Error) throw state.exception
                withContext(Dispatchers.Main) {
                    engineReady = true
                    val saved = prefs.getString("active_model", null)?.let { File(modelDir, it) }
                    if (saved != null && saved.isFile) loadModel(saved)
                    else onStatus("Mesin lokal siap · pilih model GGUF")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Mesin AI belum siap: ${shortError(e)}") }
            }
        }
    }

    fun importModel(uri: Uri) {
        if (!engineReady || busy) {
            onStatus(if (!engineReady) "Mesin AI masih disiapkan." else "Tunggu proses AI selesai.")
            return
        }
        busy = true
        onStatus("Memeriksa dan menyalin GGUF…")
        scope.launch(Dispatchers.IO) {
            var target: File? = null
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // Match Sandev Chat's known-good loader: validate the GGUF magic only.
                    // The Java metadata reader has its own architecture allowlist and can
                    // reject newer model families before llama.cpp gets a chance to load them.
                    val header = ByteArray(4)
                    var read = 0
                    while (read < header.size) {
                        val count = input.read(header, read, header.size - read)
                        if (count < 0) break
                        read += count
                    }
                    require(
                        read == 4 &&
                            header[0] == 'G'.code.toByte() &&
                            header[1] == 'G'.code.toByte() &&
                            header[2] == 'U'.code.toByte() &&
                            header[3] == 'F'.code.toByte()
                    ) { "File tidak memiliki header GGUF yang valid." }
                } ?: error("File tidak dapat dibaca sebagai GGUF.")
                val display = displayName(context.contentResolver, uri) ?: "gemma-local.gguf"
                val safe = display.replace(Regex("[^a-zA-Z0-9._() -]"), "_").trim().ifBlank { "gemma-local.gguf" }
                modelDir.mkdirs()
                target = File(modelDir, safe)
                val temp = File(modelDir, "$safe.importing")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: error("File model tidak bisa disalin.")
                if (temp.length() < 1024L * 1024L) {
                    temp.delete()
                    error("Ukuran file terlalu kecil untuk model GGUF.")
                }
                if (target.exists() && !target.delete()) error("Tidak bisa mengganti file model lama.")
                if (!temp.renameTo(target)) error("Gagal menyimpan file model.")
                withContext(Dispatchers.Main) { busy = false; loadModel(target!!) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    onStatus("Impor model gagal: ${shortError(e)}")
                }
            }
        }
    }

    private fun loadModel(file: File) {
        if (!engineReady || busy || !file.isFile) return
        busy = true
        onStatus("Memuat ${file.name} · model besar perlu waktu…")
        scope.launch(Dispatchers.IO) {
            try {
                when (engine.state.value) {
                    is InferenceEngine.State.ModelReady, is InferenceEngine.State.Error -> engine.cleanUp()
                    is InferenceEngine.State.Initialized -> Unit
                    else -> error("Mesin masih menjalankan tugas lain.")
                }
                engine.loadModel(file.absolutePath)
                engine.setSystemPrompt(
                    "Kamu adalah Gemma, penyihir pendamping di STARFALL RPG. Berbahasa Indonesia yang hangat, ringkas, dan konsisten. " +
                    "Untuk keputusan taktis, patuhi daftar aksi yang diberikan dan jangan mengklaim aksi sudah terjadi sebelum game menjalankannya. " +
                    "Kamu tidak punya akses selain status permainan yang diberikan."
                )
                engine.setSamplingParams(0.65f, 0.9f, 40, 1.08f)
                withContext(Dispatchers.Main) {
                    activeModel = file
                    modelReady = true
                    prefs.edit().putString("active_model", file.name).apply()
                    busy = false
                    onStatus("Gemma aktif · ${file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    modelReady = false
                    busy = false
                    onStatus("Model gagal dimuat: ${shortError(e)}")
                }
            }
        }
    }

    fun chat(message: String, gameState: String) {
        val clean = message.trim().take(600)
        if (clean.isBlank()) return
        if (!modelReady || busy) {
            onStatus(if (!modelReady) "Impor dan aktifkan model Gemma dahulu." else "Gemma sedang berpikir; coba sebentar lagi.")
            return
        }
        busy = true
        onStatus("Gemma sedang membalas…")
        scope.launch(Dispatchers.IO) {
            try {
                val prompt = buildString {
                    appendLine("MODE: CHAT DALAM GAME. Balas pesan pemain secara alami sebagai Gemma, pendamping penyihir.")
                    appendLine("Kondisi game saat ini: $gameState")
                    appendLine("Jangan mengubah HP, posisi, item, atau progres. Hanya game engine yang menjalankan aksi.")
                    history.takeLast(8).forEach { (role, text) -> appendLine("${if (role == "user") "Pemain" else "Gemma"}: $text") }
                    appendLine("Pemain: $clean")
                    append("Gemma:")
                }
                val output = StringBuilder()
                withTimeout(45_000L) { engine.sendUserPrompt(prompt, predictLength = 112).collect { output.append(it) } }
                val reply = output.toString().replace(Regex("<\\|[^>]+\\|>"), "").trim()
                    .takeIf { it.isNotBlank() } ?: "Maaf, pikiranku sempat buyar. Coba tanya lagi, ya."
                withContext(Dispatchers.Main) {
                    history.add("user" to clean)
                    history.add("assistant" to reply)
                    if (history.size > 20) history.subList(0, history.size - 20).clear()
                    busy = false
                    onStatus("Gemma aktif · chat lokal")
                    onChatReply(reply)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    onStatus("Chat Gemma gagal: ${shortError(e)}")
                    onChatReply("Maaf, aku belum bisa menjawab sekarang. Coba lagi sebentar ya.")
                }
            }
        }
    }

    /** Call sparingly (roughly every 10–15 seconds), never from a frame-by-frame action. */
    fun requestTactic(gameState: String) {
        if (!modelReady || busy || tacticJob?.isActive == true) return
        busy = true
        tacticJob = scope.launch(Dispatchers.IO) {
            var action = "FOLLOW"
            var comment = "Aku tetap di dekatmu."
            try {
                val prompt = "MODE: KEPUTUSAN TAKTIS STARFALL RPG. Status: $gameState\n" +
                    "Pilih satu aksi untuk Gemma berdasarkan situasi. Balas tepat dua baris:\n" +
                    "ACTION: ATTACK atau KEEP_DISTANCE atau FOLLOW atau GUARD\n" +
                    "COMMENT: komentar pendek bahasa Indonesia.\n" +
                    "Aksi hanya usulan untuk game engine; jangan keluarkan aksi lain."
                val output = StringBuilder()
                withTimeout(22_000L) { engine.sendUserPrompt(prompt, predictLength = 56).collect { output.append(it) } }
                val raw = output.toString()
                action = Regex("(?im)^\\s*ACTION\\s*:\\s*(ATTACK|KEEP_DISTANCE|FOLLOW|GUARD)\\b")
                    .find(raw)?.groupValues?.get(1)?.uppercase() ?: "FOLLOW"
                comment = Regex("(?im)^\\s*COMMENT\\s*:\\s*(.+)$").find(raw)?.groupValues?.get(1)
                    ?.trim()?.take(100) ?: "Aku siap membantumu."
            } catch (_: Exception) {
                // Deterministic fallback: stay near the player; never block the real-time game.
                action = "FOLLOW"
            }
            withContext(Dispatchers.Main) {
                busy = false
                onTactic(action, comment)
            }
        }
    }

    fun isModelReady(): Boolean = modelReady
    fun activeModelName(): String = activeModel?.name ?: "Belum ada model GGUF"

    fun close() {
        scope.cancel()
        if (::engine.isInitialized) {
            try {
                if (engine.state.value is InferenceEngine.State.ModelReady || engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
            } catch (_: Exception) {}
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun shortError(e: Exception) = (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(140)
}
