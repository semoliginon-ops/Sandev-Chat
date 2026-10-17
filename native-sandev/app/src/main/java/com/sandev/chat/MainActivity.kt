package com.sandev.chat

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatColumn: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var profileImage: ImageView
    private lateinit var engine: InferenceEngine
    private var engineReady = false
    private var generationJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMusicName: String? = null
    private val messages = mutableListOf<ChatMessage>()
    private val bots = mutableListOf<BotProfile>()
    private var activeBotId = "default"
    private var activeModelName: String? = null
    private var themeIndex = 0
    private var profilePath: String? = null
    private var backgroundPath: String? = null
    private var currentAssistantView: TextView? = null

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImage(uri, true)
    }
    private val backgroundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImage(uri, false)
    }
    private val musicPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMusic(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLocalState()
        buildUi()
        renderMessages()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                withContext(Dispatchers.Main) {
                    status.text = "Siap. Pilih model GGUF untuk mulai."
                    engineReady = true
                    activeModelName?.let { name ->
                        val model = modelFile(name)
                        if (model.exists()) loadModelFile(model, false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "Engine belum siap: ${e.message ?: "error"}"
                }
            }
        }
    }

    private fun buildUi() {
        val palette = palettes[themeIndex]
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(palette.background)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        profileImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.panel)
            setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        }
        top.addView(profileImage)
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header = TextView(this).apply {
            text = activeBot().name
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        }
        status = TextView(this).apply {
            text = "Menyiapkan engine..."
            textSize = 12f
            setTextColor(palette.muted)
        }
        titleColumn.addView(header)
        titleColumn.addView(status)
        top.addView(titleColumn)
        root.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addAction(actions, "Model") { showModelMenu() }
        addAction(actions, "Bot") { showBotMenu() }
        addAction(actions, "Tema") { cycleTheme() }
        addAction(actions, "Latar") { backgroundPicker.launch(arrayOf("image/*")) }
        addAction(actions, "Musik") { showMusicMenu() }
        root.addView(actions)

        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            setBackgroundColor(palette.panel)
        }
        chatColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        chatScroll.addView(chatColumn)
        root.addView(chatScroll)

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Tulis pesan..."
            textSize = 15f
            singleLine = false
            minLines = 1
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setBackgroundColor(palette.panel)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sendButton = Button(this).apply {
            text = "Kirim"
            setOnClickListener { sendMessage() }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
        composer.addView(input)
        composer.addView(sendButton)
        root.addView(composer)
        setContentView(root)
        applyPalette()
        applyImages()
    }

    private fun addAction(row: LinearLayout, label: String, action: () -> Unit) {
        row.addView(Button(this).apply {
            text = label
            textSize = 11f
            minWidth = 0
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
    }

    private fun showModelMenu() {
        val models = modelFiles()
        val options = arrayOf("Impor model GGUF", "Pilih model tersimpan", "Hapus model")
        AlertDialog.Builder(this).setTitle("Model Q4_K_M GGUF")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> modelPicker.launch(arrayOf("*/*"))
                    1 -> showSavedModels(models, false)
                    2 -> showSavedModels(models, true)
                }
            }.show()
    }

    private fun showSavedModels(models: List<File>, deleting: Boolean) {
        if (models.isEmpty()) {
            toast("Belum ada model tersimpan.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (deleting) "Pilih model untuk dihapus" else "Pilih model")
            .setItems(models.map { "${it.name}  •  ${formatSize(it.length())}" }.toTypedArray()) { _, index ->
                val file = models[index]
                if (deleting) {
                    AlertDialog.Builder(this).setTitle("Hapus model?")
                        .setMessage("File ${file.name} akan dihapus dari penyimpanan aplikasi.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hapus") { _, _ -> deleteModel(file) }.show()
                } else {
                    activeModelName = file.name
                    prefs().edit().putString(KEY_ACTIVE_MODEL, file.name).apply()
                    loadModelFile(file, true)
                }
            }.show()
    }

    private fun importModel(uri: Uri) {
        val originalName = displayName(contentResolver, uri) ?: "model.gguf"
        if (!originalName.lowercase().endsWith(".gguf")) {
            toast("Pilih file model berformat .gguf.")
            return
        }
        status.text = "Memeriksa metadata Q4_K_M..."
        sendButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(uri)?.use { GgufMetadataReader.create().readStructuredMetadata(it) }
                    ?: error("File model tidak bisa dibaca.")
                if (metadata.architecture?.fileType != 15) {
                    withContext(Dispatchers.Main) {
                        status.text = "Model ditolak: bukan Q4_K_M."
                        toast("Pilih GGUF dengan quantization Q4_K_M.")
                        sendButton.isEnabled = true
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) { status.text = "Menyalin model ke penyimpanan aplikasi..." }
                val dir = modelsDir().apply { mkdirs() }
                val safeName = safeFileName(originalName)
                var target = File(dir, safeName)
                var suffix = 2
                while (target.exists()) {
                    target = File(dir, "${safeName.removeSuffix(".gguf")} (${suffix++}).gguf")
                }
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "File tidak bisa dibuka." }
                    FileOutputStream(target).use { sink -> source.copyTo(sink) }
                }
                withContext(Dispatchers.Main) {
                    activeModelName = target.name
                    prefs().edit().putString(KEY_ACTIVE_MODEL, target.name).apply()
                    status.text = "Model tersimpan. Memuat model..."
                    loadModelFile(target, true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "Impor gagal."
                    toast("Gagal menyimpan model: ${e.message}")
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun loadModelFile(file: File, showToast: Boolean) {
        if (!engineReady) {
            status.text = "Engine masih disiapkan..."
            return
        }
        sendButton.isEnabled = false
        input.isEnabled = false
        status.text = "Memuat ${file.name}..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.loadModel(file.absolutePath)
                val bot = activeBot()
                engine.setSystemPrompt(bot.systemPrompt)
                withContext(Dispatchers.Main) {
                    activeModelName = file.name
                    status.text = "Model aktif: ${file.name}"
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    if (showToast) toast("Model siap digunakan.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "Model gagal dimuat: ${e.message ?: "periksa format/model"}"
                    input.isEnabled = true
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun deleteModel(file: File) {
        if (activeModelName == file.name && engineReady) {
            lifecycleScope.launch(Dispatchers.IO) {
                try { engine.cleanUp() } catch (_: Exception) {}
                withContext(Dispatchers.Main) { finishDelete(file) }
            }
        } else finishDelete(file)
    }

    private fun finishDelete(file: File) {
        val removed = file.delete()
        if (removed && activeModelName == file.name) {
            activeModelName = null
            prefs().edit().remove(KEY_ACTIVE_MODEL).apply()
            status.text = "Model dihapus. Pilih model lain."
        }
        toast(if (removed) "Model dihapus." else "Model tidak bisa dihapus.")
    }

    private fun showBotMenu() {
        val options = arrayOf("Pilih bot", "Buat bot baru")
        AlertDialog.Builder(this).setTitle("Bot")
            .setItems(options) { _, which ->
                if (which == 0) chooseBot() else createBot()
            }.show()
    }

    private fun chooseBot() {
        val list = bots.toList()
        if (list.isEmpty()) { createBot(); return }
        AlertDialog.Builder(this).setTitle("Pilih bot")
            .setItems(list.map { it.name }.toTypedArray()) { _, index ->
                activeBotId = list[index].id
                prefs().edit().putString(KEY_ACTIVE_BOT, activeBotId).apply()
                header.text = list[index].name
                applyImages()
                if (engineReady && activeModelName != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { engine.setSystemPrompt(list[index].systemPrompt) } catch (_: Exception) {}
                    }
                }
            }.show()
    }

    private fun createBot() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply { hint = "Nama bot" }
        val prompt = EditText(this).apply {
            hint = "Kepribadian / instruksi bot"
            minLines = 3
            gravity = Gravity.TOP
        }
        form.addView(name)
        form.addView(prompt)
        AlertDialog.Builder(this).setTitle("Buat bot").setView(form)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val botName = name.text.toString().trim().ifBlank { "Bot ${bots.size + 1}" }
                val bot = BotProfile(UUID.randomUUID().toString(), botName, prompt.text.toString().trim())
                bots.add(bot)
                activeBotId = bot.id
                saveBots()
                prefs().edit().putString(KEY_ACTIVE_BOT, activeBotId).apply()
                header.text = bot.name
                applyImages()
                if (engineReady && activeModelName != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { engine.setSystemPrompt(bot.systemPrompt) } catch (_: Exception) {}
                    }
                }
            }.show()
    }

    private fun cycleTheme() {
        themeIndex = (themeIndex + 1) % palettes.size
        prefs().edit().putInt(KEY_THEME, themeIndex).apply()
        applyPalette()
    }

    private fun applyPalette() {
        if (!::root.isInitialized) return
        val p = palettes[themeIndex]
        root.setBackgroundColor(p.background)
        header.setTextColor(p.text)
        status.setTextColor(p.muted)
        if (backgroundPath.isNullOrBlank()) {
            chatScroll.setBackgroundColor(p.panel)
            chatColumn.setBackgroundColor(p.panel)
        } else {
            chatColumn.setBackgroundColor(Color.TRANSPARENT)
            applyImages()
        }
        input.setTextColor(p.text)
        input.setHintTextColor(p.muted)
        input.setBackgroundColor(p.panel)
        profileImage.setBackgroundColor(p.panel)
        sendButton.setTextColor(Color.WHITE)
        sendButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.accent))
        renderMessages()
    }

    private fun importImage(uri: Uri, profile: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "custom").apply { mkdirs() }
                val target = File(dir, if (profile) "bot-${activeBotId}-profile" else "chat-background")
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "Gambar tidak bisa dibuka." }
                    FileOutputStream(target).use { sink -> source.copyTo(sink) }
                }
                withContext(Dispatchers.Main) {
                    if (profile) {
                        profilePath = target.absolutePath
                        prefs().edit().putString(KEY_PROFILE, profilePath).apply()
                        val botIndex = bots.indexOfFirst { it.id == activeBotId }
                        if (botIndex >= 0) {
                            bots[botIndex] = bots[botIndex].copy(avatarPath = target.absolutePath)
                            saveBots()
                        }
                    } else {
                        backgroundPath = target.absolutePath
                        prefs().edit().putString(KEY_BACKGROUND, backgroundPath).apply()
                    }
                    applyImages()
                    toast(if (profile) "Foto profil disimpan." else "Background chat disimpan.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Gagal menyimpan gambar: ${e.message}") }
            }
        }
    }

    private fun applyImages() {
        if (!::profileImage.isInitialized) return
        val avatar = activeBot().avatarPath
        if (!avatar.isNullOrBlank() && File(avatar).exists()) {
            BitmapFactory.decodeFile(avatar)?.let { profileImage.setImageBitmap(it) }
        } else {
            profileImage.setImageDrawable(null)
        }
        backgroundPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    chatScroll.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
                }
            }
        }
    }

    private fun showMusicMenu() {
        val options = arrayOf("Pilih file musik", "Putar / jeda", "Hentikan musik")
        AlertDialog.Builder(this).setTitle(currentMusicName ?: "Pemutar musik")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> musicPicker.launch(arrayOf("audio/*"))
                    1 -> toggleMusic()
                    2 -> stopMusic()
                }
            }.show()
    }

    private fun importMusic(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "music").apply { mkdirs() }
                val name = safeFileName(displayName(contentResolver, uri) ?: "music-audio")
                val target = File(dir, name)
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "File audio tidak bisa dibuka." }
                    FileOutputStream(target).use { sink -> source.copyTo(sink) }
                }
                withContext(Dispatchers.Main) {
                    stopMusic()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(target.absolutePath)
                        setOnPreparedListener { it.start(); toast("Memutar ${target.name}") }
                        setOnErrorListener { _, _, _ -> toast("File musik tidak dapat diputar."); true }
                        prepareAsync()
                    }
                    currentMusicName = target.name
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Musik gagal dibuka: ${e.message}") }
            }
        }
    }

    private fun toggleMusic() {
        val player = mediaPlayer
        if (player == null) { toast("Pilih file musik dulu."); return }
        if (player.isPlaying) player.pause() else player.start()
    }

    private fun stopMusic() {
        mediaPlayer?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
        currentMusicName = null
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!engineReady || activeModelName == null) {
            toast("Pilih atau impor model GGUF terlebih dahulu.")
            return
        }
        val model = activeModelName?.let { modelFile(it) }
        if (model == null || !model.exists()) {
            toast("File model tidak ditemukan. Pilih ulang model.")
            return
        }
        input.setText("")
        messages.add(ChatMessage("user", text))
        val assistant = ChatMessage("assistant", "")
        messages.add(assistant)
        saveMessages()
        renderMessages()
        currentAssistantView = chatColumn.getChildAt(chatColumn.childCount - 1) as? TextView
        input.isEnabled = false
        sendButton.isEnabled = false
        status.text = "Bot sedang mengetik..."
        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.sendUserPrompt(text).collect { token ->
                    assistant.text += token
                    withContext(Dispatchers.Main) {
                        currentAssistantView?.text = "Bot: ${assistant.text}"
                        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                saveMessages()
                withContext(Dispatchers.Main) {
                    status.text = "Model aktif: ${activeModelName}"
                }
            } catch (e: Exception) {
                assistant.text = if (assistant.text.isBlank()) "Gagal menghasilkan jawaban: ${e.message}" else assistant.text
                saveMessages()
                withContext(Dispatchers.Main) {
                    currentAssistantView?.text = "Bot: ${assistant.text}"
                    status.text = "Jawaban selesai dengan kendala."
                }
            } finally {
                withContext(Dispatchers.Main) {
                    input.isEnabled = true
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun renderMessages() {
        if (!::chatColumn.isInitialized) return
        chatColumn.removeAllViews()
        val p = palettes[themeIndex]
        messages.forEach { message ->
            val tv = TextView(this).apply {
                text = (if (message.role == "user") "Kamu: " else "Bot: ") + message.text
                textSize = 15f
                setTextColor(p.text)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(if (message.role == "user") p.userBubble else p.botBubble)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            chatColumn.addView(tv, lp)
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadLocalState() {
        themeIndex = prefs().getInt(KEY_THEME, 0).coerceIn(0, palettes.lastIndex)
        activeModelName = prefs().getString(KEY_ACTIVE_MODEL, null)
        activeBotId = prefs().getString(KEY_ACTIVE_BOT, "default") ?: "default"
        profilePath = prefs().getString(KEY_PROFILE, null)
        backgroundPath = prefs().getString(KEY_BACKGROUND, null)
        val rawMessages = prefs().getString(KEY_MESSAGES, "[]") ?: "[]"
        try {
            val array = JSONArray(rawMessages)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                messages.add(ChatMessage(item.optString("role"), item.optString("text")))
            }
        } catch (_: Exception) {}
        val rawBots = prefs().getString(KEY_BOTS, "[]") ?: "[]"
        try {
            val array = JSONArray(rawBots)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                bots.add(BotProfile(item.optString("id"), item.optString("name"), item.optString("prompt"), item.optString("avatar").takeIf { it.isNotBlank() }))
            }
        } catch (_: Exception) {}
        if (bots.none { it.id == "default" }) bots.add(0, BotProfile("default", "Sandev", ""))
    }

    private fun saveMessages() {
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("role", it.role).put("text", it.text)) }
        prefs().edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    private fun saveBots() {
        val array = JSONArray()
        bots.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.systemPrompt).put("avatar", it.avatarPath ?: "")) }
        prefs().edit().putString(KEY_BOTS, array.toString()).apply()
    }

    private fun activeBot(): BotProfile = bots.firstOrNull { it.id == activeBotId } ?: bots.first()

    private fun modelsDir() = File(filesDir, "models")
    private fun modelFiles() = modelsDir().listFiles { f -> f.isFile && f.extension.equals("gguf", true) }?.sortedBy { it.name } ?: emptyList()
    private fun modelFile(name: String) = File(modelsDir(), name)
    private fun prefs() = getSharedPreferences("sandev_native", Context.MODE_PRIVATE)
    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    private fun safeFileName(value: String) = value.replace(Regex("[^a-zA-Z0-9._() -]"), "_").trim().ifBlank { "file" }
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f KB", bytes / 1024.0)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        generationJob?.cancel()
        stopMusic()
        if (::engine.isInitialized) {
            try { engine.destroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private data class ChatMessage(val role: String, var text: String)
    private data class BotProfile(val id: String, val name: String, val systemPrompt: String, val avatarPath: String? = null)
    private data class Palette(
        val background: Int, val panel: Int, val text: Int, val muted: Int,
        val accent: Int, val userBubble: Int, val botBubble: Int
    )

    companion object {
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_ACTIVE_BOT = "active_bot"
        private const val KEY_THEME = "theme"
        private const val KEY_PROFILE = "profile"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_BOTS = "bots"
        private val palettes = listOf(
            Palette(Color.rgb(16,19,25), Color.rgb(29,34,44), Color.rgb(245,246,250), Color.rgb(166,174,190), Color.rgb(139,124,255), Color.rgb(48,54,72), Color.rgb(36,43,56)),
            Palette(Color.rgb(248,249,252), Color.rgb(255,255,255), Color.rgb(29,33,42), Color.rgb(105,112,124), Color.rgb(48,110,230), Color.rgb(220,234,255), Color.rgb(236,239,245)),
            Palette(Color.rgb(16,28,35), Color.rgb(26,43,52), Color.rgb(236,247,250), Color.rgb(151,180,190), Color.rgb(32,190,170), Color.rgb(31,76,75), Color.rgb(33,55,67)),
            Palette(Color.rgb(30,18,35), Color.rgb(47,30,54), Color.rgb(250,241,255), Color.rgb(192,165,203), Color.rgb(221,112,255), Color.rgb(81,42,91), Color.rgb(59,39,69))
        )
    }
}
