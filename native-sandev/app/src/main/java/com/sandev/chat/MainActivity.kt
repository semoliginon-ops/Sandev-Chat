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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private lateinit var composer: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var profileImage: ImageView
    private lateinit var engine: InferenceEngine
    private var engineReady = false
    private var modelLoadInProgress = false
    private var modelImportInProgress = false
    private var generationJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMusicName: String? = null
    private val messages = mutableListOf<ChatMessage>()
    private val bots = mutableListOf<BotProfile>()
    private var activeBotId = "default"
    private var activeModelName: String? = null
    private var pendingModelFile: File? = null
    private var themeIndex = 0
    private var profilePath: String? = null
    private var backgroundPath: String? = null
    private var currentAssistantView: TextView? = null
    private var currentPage = "discover"
    private var userName = "You"
    private var userHandle = ""
    private var userBio = ""
    private var userPersona = ""

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImage(uri, true)
    }
    private val backgroundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImage(uri, false)
    }
    private val userImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUserImage(uri)
    }
    private val musicPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMusic(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        loadLocalState()
        buildUi()
        renderPage()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                val initialState = withTimeout(45_000) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized ||
                            it is InferenceEngine.State.ModelReady ||
                            it is InferenceEngine.State.Error
                    }
                }
                if (initialState is InferenceEngine.State.Error) {
                    throw initialState.exception
                }
                withContext(Dispatchers.Main) {
                    status.text = "Siap. Pilih model Q4_K_M GGUF."
                    engineReady = true
                    val pending = pendingModelFile
                    if (pending != null && pending.exists()) {
                        pendingModelFile = null
                        loadModelFile(pending, true)
                    } else if (prefs().getBoolean(KEY_AUTO_LOAD_MODEL, true)) {
                        activeModelName?.let { name ->
                            val model = modelFile(name)
                            if (model.exists()) loadModelFile(model, false)
                            else status.text = "Model terakhir tidak ditemukan. Pilih model."
                        }
                    } else {
                        status.text = "Engine siap. Pilih model untuk mulai."
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
        addAction(actions, "AI Model") { showModelMenu() }
        addAction(actions, "Pengaturan") { showSettingsMenu() }
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

        composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Tulis pesan..."
            textSize = 16f
            isSingleLine = false
            minLines = 1
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(palette.panel)
                setStroke(dp(1), palette.muted)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
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

        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        addNavItem(navRow, "⌂", "Discover") { currentPage = "discover"; renderPage() }
        addNavItem(navRow, "▤", "Chats") { currentPage = "chats"; renderPage() }
        addNavItem(navRow, "♫", "Music") { currentPage = "music"; renderPage() }
        addNavItem(navRow, "＋", "Create Bot") { currentPage = "create_bot"; renderPage() }
        addNavItem(navRow, "◉", "Profile") { currentPage = "profile"; renderPage() }
        root.addView(navRow)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(dp(12), bars.top + dp(8), dp(12), maxOf(bars.bottom, ime.bottom) + dp(8))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        applyPalette()
        applyImages()
    }

    private fun addAction(row: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 12f
            minWidth = 0
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { action() }
        }
        val params = if (row.orientation == LinearLayout.VERTICAL) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        } else {
            LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        row.addView(button, params)
    }

    private fun addNavItem(row: LinearLayout, symbol: String, label: String, action: () -> Unit) {
        val p = palettes[themeIndex]
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(5))
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
        }
        item.addView(TextView(this).apply {
            text = symbol
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(p.muted)
        })
        item.addView(TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(p.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f))
    }

    private fun updateNavigationColors() {
        if (!::navRow.isInitialized) return
        val p = palettes[themeIndex]
        for (index in 0 until navRow.childCount) {
            val item = navRow.getChildAt(index) as? LinearLayout ?: continue
            val label = (item.getChildAt(1) as? TextView)?.text?.toString().orEmpty()
            val selected = when (label) {
                "Discover" -> currentPage == "discover"
                "Chats" -> currentPage == "chats" || currentPage == "conversation"
                "Create Bot" -> currentPage == "create_bot"
                "Music" -> currentPage == "music"
                "Profile" -> currentPage == "profile"
                else -> false
            }
            (item.getChildAt(0) as? TextView)?.setTextColor(if (selected) p.accent else p.muted)
            (item.getChildAt(1) as? TextView)?.setTextColor(if (selected) p.accent else p.text)
            item.setBackgroundColor(if (selected) p.panel else Color.TRANSPARENT)
        }
    }

    private fun showSettingsMenu() {
        val options = arrayOf("AI Model", "Ganti tema", "Foto latar chat", "Profil pengguna")
        AlertDialog.Builder(this).setTitle("Pengaturan Sandev")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showModelMenu()
                    1 -> cycleTheme()
                    2 -> backgroundPicker.launch(arrayOf("image/*"))
                    3 -> { currentPage = "profile"; renderPage() }
                }
            }.show()
    }

    private fun renderPage() {
        if (!::chatColumn.isInitialized) return
        val inConversation = currentPage == "conversation"
        // Insets on the root reserve the real IME height, keeping the composer above the keyboard.
        composer.visibility = if (inConversation) View.VISIBLE else View.GONE
        navRow.visibility = if (inConversation) View.GONE else View.VISIBLE
        chatColumn.removeAllViews()
        when (currentPage) {
            "discover" -> renderDiscover()
            "chats" -> renderChatHistory()
            "conversation" -> { header.text = activeBot().name; renderMessages() }
            "music" -> renderMusicPage()
            "create_bot" -> renderCreateBotPage()
            "profile" -> renderUserProfile()
            else -> renderDiscover()
        }
        navRow.setBackgroundColor(palettes[themeIndex].background)
        updateNavigationColors()
        applyImages()
    }

    private fun addSectionTitle(title: String, subtitle: String? = null) {
        val p = palettes[themeIndex]
        chatColumn.addView(TextView(this).apply {
            text = title; textSize = 23f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text); setPadding(dp(4), dp(8), dp(4), dp(4))
        })
        if (!subtitle.isNullOrBlank()) chatColumn.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(p.muted)
            setPadding(dp(4), 0, dp(4), dp(12))
        })
    }

    private fun renderDiscover() {
        header.text = "Discover"
        val p = palettes[themeIndex]
        addSectionTitle("Temukan karakter", "Pilih teman ngobrol, roleplay, atau karakter buatanmu sendiri.")

        val feature = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = panelBackground(p.panel, p.accent, 20)
        }
        feature.addView(TextView(this).apply {
            text = "RUANG KARAKTER"
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.accent)
        })
        feature.addView(TextView(this).apply {
            text = "Cerita dimulai dari satu percakapan."
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            setPadding(0, dp(6), 0, dp(4))
        })
        feature.addView(TextView(this).apply {
            text = "Temukan karakter yang cocok atau ciptakan karakter versimu."
            textSize = 13f
            setTextColor(p.muted)
        })
        feature.addView(TextView(this).apply {
            text = "＋  Buat karakter baru"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.accent)
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            setOnClickListener { currentPage = "create_bot"; renderPage() }
        })
        chatColumn.addView(feature, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })

        val search = EditText(this).apply {
            hint = "⌕  Cari nama atau deskripsi karakter..."
            setSingleLine(true)
            textSize = 14f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelBackground(p.panel, p.muted, 14)
        }
        chatColumn.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        var selectedCategory = "Semua"
        val categories = listOf("Semua", "Roleplay", "Fantasi", "Romansa", "Buatanmu")
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryRow)
        }
        chatColumn.addView(categoryScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        chatColumn.addView(TextView(this).apply {
            text = "KARAKTER UNTUKMU"
            textSize = 11f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.muted)
            setPadding(dp(2), 0, dp(2), dp(8))
        })
        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatColumn.addView(cards)
        fun populate(filter: String) {
            cards.removeAllViews()
            val visible = bots.filter { bot ->
                val queryMatches = filter.isBlank() || bot.name.contains(filter, true) ||
                    bot.tagline.contains(filter, true) || bot.description.contains(filter, true)
                val searchable = listOf(bot.name, bot.tagline, bot.description, bot.systemPrompt)
                val categoryMatches = when (selectedCategory) {
                    "Roleplay" -> bot.id != "default" && (bot.description.isNotBlank() || bot.systemPrompt.isNotBlank())
                    "Fantasi" -> listOf("fantasy", "fantasi", "sihir", "magic", "naga", "elf", "wizard", "dragon")
                        .any { keyword -> searchable.any { value -> value.contains(keyword, true) } }
                    "Romansa" -> listOf("romansa", "romantis", "romance", "cinta", "love", "pasangan")
                        .any { keyword -> searchable.any { value -> value.contains(keyword, true) } }
                    "Buatanmu" -> bot.id != "default"
                    else -> true
                }
                queryMatches && categoryMatches
            }
            visible.forEach { bot ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = panelBackground(p.panel, Color.rgb(53, 66, 88), 18)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        saveMessages()
                        activeBotId = bot.id
                        prefs().edit().putString(KEY_ACTIVE_BOT, activeBotId).apply()
                        loadMessagesForBot(activeBotId)
                        currentPage = "conversation"
                        if (bot.greeting.isNotBlank() && messages.isEmpty()) {
                            messages.add(ChatMessage("assistant", bot.greeting))
                            saveMessages()
                        }
                        if (engineReady && activeModelName != null) lifecycleScope.launch {
                            try { engine.setSystemPrompt(buildCharacterSystemPrompt(bot)) } catch (_: Exception) {}
                        }
                        renderPage()
                    }
                }
                val avatar = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(84))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = panelBackground(p.background, p.muted, 14)
                    bot.avatarPath?.let { path ->
                        if (File(path).exists()) decodeScaledBitmap(path, 192)?.let { setImageBitmap(it) }
                    }
                }
                card.addView(avatar)
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(this).apply {
                    text = bot.name
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(p.text)
                })
                textCol.addView(TextView(this).apply {
                    text = bot.tagline.ifBlank { bot.description.ifBlank { "Karakter offline Sandev" } }
                    textSize = 12f
                    setTextColor(p.muted)
                    maxLines = 3
                    setPadding(0, dp(4), 0, 0)
                })
                textCol.addView(TextView(this).apply {
                    text = "MULAI CHAT  ›"
                    textSize = 11f
                    letterSpacing = 0.04f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(p.accent)
                    setPadding(0, dp(8), 0, 0)
                })
                card.addView(textCol)
                cards.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            if (visible.isEmpty()) cards.addView(TextView(this).apply {
                text = "Karakter tidak ditemukan. Coba kata lain atau buat karakter baru."
                textSize = 14f
                setTextColor(p.muted)
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
        }
        fun buildCategoryChips() {
            categoryRow.removeAllViews()
            categories.forEach { category ->
                val selected = category == selectedCategory
                val chip = TextView(this).apply {
                    text = category
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setTextColor(if (selected) Color.rgb(10, 16, 29) else p.text)
                    background = panelBackground(if (selected) p.accent else p.panel, if (selected) p.accent else p.muted, 24)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedCategory = category
                        buildCategoryChips()
                        populate(search.text.toString())
                    }
                }
                categoryRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        buildCategoryChips()
        populate("")
    }

    private fun renderMusicPage() {
        header.text = "Music"
        addSectionTitle("Musik", "Pilih musik lokal untuk menemani percakapan.")
        val p = palettes[themeIndex]
        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = panelBackground(p.panel, Color.rgb(53, 66, 88), 18)
        }
        playerCard.addView(TextView(this).apply { text = "♫"; textSize = 32f; setTextColor(p.accent) })
        playerCard.addView(TextView(this).apply {
            text = currentMusicName?.let { it } ?: "Belum ada musik dipilih"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            setPadding(0, dp(8), 0, dp(4))
        })
        playerCard.addView(TextView(this).apply {
            text = "Audio dari perangkatmu"
            textSize = 12f
            setTextColor(p.muted)
        })
        chatColumn.addView(playerCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        addAction(chatColumn, "Pilih file musik") { musicPicker.launch(arrayOf("audio/*")) }
        addAction(chatColumn, "Putar / jeda") { toggleMusic() }
        addAction(chatColumn, "Hentikan musik") { stopMusic(); renderPage() }
    }

    private fun renderUserProfile() {
        header.text = "Profil pengguna"
        addSectionTitle("Persona kamu", "Atur identitas yang ingin kamu bawa ke dalam percakapan.")
        val p = palettes[themeIndex]
        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = panelBackground(p.panel, Color.rgb(53, 66, 88), 20)
        }
        val avatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(100)).apply { bottomMargin = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = panelBackground(p.background, p.accent, 50)
            profilePath?.let { path -> if (File(path).exists()) decodeScaledBitmap(path, 256)?.let { setImageBitmap(it) } }
        }
        profileCard.addView(avatar)
        profileCard.addView(TextView(this).apply {
            text = userName.ifBlank { "You" }
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
        })
        profileCard.addView(TextView(this).apply {
            text = userHandle.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "PROFIL LOKAL"
            textSize = 12f
            setTextColor(p.accent)
            setPadding(0, dp(3), 0, dp(8))
        })
        addAction(profileCard, "Ganti foto pengguna") { userImagePicker.launch(arrayOf("image/*")) }
        chatColumn.addView(profileCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        fun profileField(hintText: String, value: String, lines: Int = 1) = EditText(this).apply {
            hint = hintText
            setText(value)
            textSize = 14f
            minLines = lines
            maxLines = if (lines == 1) 2 else lines + 2
            gravity = if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelBackground(p.panel, p.muted, 12)
        }
        val name = profileField("Nama tampilan", userName)
        val handle = profileField("Username (opsional)", userHandle)
        val bio = profileField("Bio singkat", userBio, 2)
        val persona = profileField("Persona / informasi diri untuk roleplay", userPersona, 3)
        listOf(name, handle, bio, persona).forEach { field ->
            chatColumn.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        addAction(chatColumn, "Simpan profil") {
            userName = name.text.toString().trim().ifBlank { "You" }
            userHandle = handle.text.toString().trim().removePrefix("@")
            userBio = bio.text.toString().trim()
            userPersona = persona.text.toString().trim()
            prefs().edit().putString(KEY_USER_NAME, userName).putString(KEY_USER_HANDLE, userHandle)
                .putString(KEY_USER_BIO, userBio).putString(KEY_USER_PERSONA, userPersona).apply()
            toast("Profil pengguna tersimpan.")
            renderPage()
        }
    }

    private fun showModelMenu() {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
        val models = modelFiles()
        val options = arrayOf(
            "＋  Impor model GGUF",
            "▤  Model tersimpan (${models.size})",
            "−  Hapus model",
            "⚙  Pengaturan model otomatis"
        )
        AlertDialog.Builder(this).setTitle("AI Model · Q4_K_M")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> modelPicker.launch(arrayOf("*/*"))
                    1 -> showSavedModels(modelFiles(), false)
                    2 -> showSavedModels(modelFiles(), true)
                    3 -> showModelSettings()
                }
            }.show()
    }

    private fun showModelSettings() {
        val autoLoad = booleanArrayOf(prefs().getBoolean(KEY_AUTO_LOAD_MODEL, true))
        AlertDialog.Builder(this)
            .setTitle("Pengaturan model")
            .setMultiChoiceItems(
                arrayOf("Muat model terakhir otomatis saat aplikasi dibuka"),
                autoLoad
            ) { _, _, checked -> autoLoad[0] = checked }
            .setPositiveButton("Simpan") { _, _ ->
                prefs().edit().putBoolean(KEY_AUTO_LOAD_MODEL, autoLoad[0]).apply()
                toast(if (autoLoad[0]) "Muat otomatis diaktifkan." else "Muat otomatis dimatikan.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSavedModels(models: List<File>, deleting: Boolean) {
        if (models.isEmpty()) {
            toast("Belum ada model tersimpan.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (deleting) "Pilih model untuk dihapus" else "Pilih AI model")
            .setItems(models.map {
                (if (it.name == activeModelName) "✓  " else "    ") + "${it.name}  •  ${formatSize(it.length())}"
            }.toTypedArray()) { _, index ->
                val file = models[index]
                if (deleting) {
                    AlertDialog.Builder(this).setTitle("Hapus model?")
                        .setMessage("File ${file.name} akan dihapus dari penyimpanan aplikasi.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hapus") { _, _ -> deleteModel(file) }.show()
                } else {
                    loadModelFile(file, true)
                }
            }.show()
    }

    private fun importModel(uri: Uri) {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
        modelImportInProgress = true
        val originalName = displayName(contentResolver, uri) ?: "model.gguf"
        if (!originalName.lowercase().endsWith(".gguf")) {
            modelImportInProgress = false
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
                    status.text = "Model tersimpan. Memuat model..."
                    loadModelFile(target, true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "Impor gagal."
                    toast("Gagal menyimpan model: ${e.message}")
                    sendButton.isEnabled = true
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelImportInProgress = false
                }
            }
        }
    }

    private fun loadModelFile(file: File, showToast: Boolean) {
        if (!engineReady) {
            pendingModelFile = file
            status.text = "Model tersimpan; menunggu engine..."
            input.isEnabled = true
            sendButton.isEnabled = true
            return
        }
        if (generationJob?.isActive == true || modelLoadInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
        modelLoadInProgress = true
        sendButton.isEnabled = false
        input.isEnabled = false
        status.text = "Memuat ${file.name}..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (engine.state.value) {
                    is InferenceEngine.State.ModelReady,
                    is InferenceEngine.State.Error -> engine.cleanUp()
                    is InferenceEngine.State.Initialized -> Unit
                    else -> error("Engine masih sibuk. Tunggu proses model selesai.")
                }
                engine.loadModel(file.absolutePath)
                engine.setSystemPrompt(buildCharacterSystemPrompt(activeBot()))
                withContext(Dispatchers.Main) {
                    activeModelName = file.name
                    prefs().edit().putString(KEY_ACTIVE_MODEL, file.name).apply()
                    status.text = "Model aktif: ${file.name}"
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    if (showToast) toast("Model siap digunakan.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activeModelName = null
                    prefs().edit().remove(KEY_ACTIVE_MODEL).apply()
                    status.text = "Model gagal dimuat: ${e.message ?: "periksa format/model"}"
                    input.isEnabled = true
                    sendButton.isEnabled = true
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelLoadInProgress = false
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
        if (generationJob?.isActive == true || modelLoadInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
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
                    val file = modelFile(activeModelName!!)
                    if (file.exists()) loadModelFile(file, false)
                }
            }.show()
    }

    private fun createBot() {
        currentPage = "create_bot"
        renderPage()
    }

    private fun renderCreateBotPage() {
        header.text = "Create Bot"
        addSectionTitle("Ciptakan karakter", "Isi detail karakter seperti membuat persona di Character.AI.")
        val p = palettes[themeIndex]
        val formCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = panelBackground(p.panel, Color.rgb(53, 66, 88), 18)
        }
        fun field(hintText: String, lines: Int = 1): EditText = EditText(this).apply {
            hint = hintText
            textSize = 14f
            minLines = lines
            maxLines = if (lines == 1) 2 else lines + 2
            gravity = if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelBackground(p.background, p.muted, 12)
        }
        val name = field("Nama karakter *")
        val tagline = field("Tagline singkat")
        val description = field("Deskripsi karakter", 3)
        val greeting = field("Greeting / sapaan awal", 3)
        val prompt = field("Kepribadian, gaya bicara, dan latar belakang", 5)
        val example = field("Contoh dialog (opsional)", 3)
        fun addLabeled(label: String, edit: EditText) {
            formCard.addView(TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.muted)
                setPadding(dp(2), dp(10), dp(2), dp(4))
            })
            formCard.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addLabeled("NAMA", name)
        addLabeled("TAGLINE", tagline)
        addLabeled("DESKRIPSI", description)
        addLabeled("SAPAAN PERTAMA", greeting)
        addLabeled("KEPRIBADIAN / SYSTEM PROMPT", prompt)
        addLabeled("CONTOH DIALOG", example)
        chatColumn.addView(formCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        addAction(chatColumn, "Simpan karakter") {
            val botName = name.text.toString().trim()
            if (botName.isBlank()) {
                name.error = "Nama karakter wajib diisi"
                toast("Isi nama karakter dulu.")
                return@addAction
            }
            val botDescription = description.text.toString().trim()
            val botGreeting = greeting.text.toString().trim()
            val exampleText = example.text.toString().trim()
            val fullPrompt = buildString {
                append(prompt.text.toString().trim())
                if (botDescription.isNotBlank()) append("\n\nDeskripsi karakter: ").append(botDescription)
                if (exampleText.isNotBlank()) append("\n\nContoh dialog:\n").append(exampleText)
                if (userPersona.isNotBlank()) append("\n\nPersona pengguna: ").append(userPersona)
            }.trim()
            val bot = BotProfile(UUID.randomUUID().toString(), botName, fullPrompt, null,
                tagline.text.toString().trim(), botDescription, botGreeting, exampleText)
            saveMessages()
            bots.add(bot)
            saveBots()
            activeBotId = bot.id
            prefs().edit().putString(KEY_ACTIVE_BOT, activeBotId).apply()
            loadMessagesForBot(activeBotId)
            if (botGreeting.isNotBlank()) {
                messages.add(ChatMessage("assistant", botGreeting))
                saveMessages()
            }
            currentPage = "conversation"
            if (engineReady && activeModelName != null) lifecycleScope.launch {
                try { engine.setSystemPrompt(buildCharacterSystemPrompt(bot)) } catch (_: Exception) {}
            }
            renderPage()
            toast("Karakter $botName tersimpan. Ketuk avatar di header untuk mengganti fotonya.")
        }
        addAction(chatColumn, "Batal") { currentPage = "discover"; renderPage() }
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
        navRow.setBackgroundColor(p.background)
        input.setTextColor(p.text)
        input.setHintTextColor(p.muted)
        input.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(p.panel)
            setStroke(dp(1), p.muted)
        }
        profileImage.setBackgroundColor(p.panel)
        sendButton.setTextColor(Color.WHITE)
        sendButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.accent))
        renderPage()
    }

    private fun importImage(uri: Uri, profile: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "custom").apply { mkdirs() }
                val target = File(dir, if (profile) "bot-${activeBotId}-avatar" else "chat-background")
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "Gambar tidak bisa dibuka." }
                    FileOutputStream(target).use { sink -> source.copyTo(sink) }
                }
                withContext(Dispatchers.Main) {
                    if (profile) {
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
                    if (currentPage == "discover") renderPage()
                    toast(if (profile) "Foto bot disimpan." else "Background chat disimpan.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Gagal menyimpan gambar: ${e.message}") }
            }
        }
    }

    private fun importUserImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "custom").apply { mkdirs() }
                val target = File(dir, "user-profile")
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "Gambar tidak bisa dibuka." }
                    FileOutputStream(target).use { sink -> source.copyTo(sink) }
                }
                withContext(Dispatchers.Main) {
                    profilePath = target.absolutePath
                    prefs().edit().putString(KEY_PROFILE, profilePath).apply()
                    toast("Foto pengguna disimpan.")
                    if (currentPage == "profile") renderPage()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Gagal menyimpan foto pengguna: ${e.message}") }
            }
        }
    }

    private fun applyImages() {
        if (!::profileImage.isInitialized) return
        val avatar = activeBot().avatarPath
        if (!avatar.isNullOrBlank() && File(avatar).exists()) {
            decodeScaledBitmap(avatar, 256)?.let { profileImage.setImageBitmap(it) }
        } else {
            profileImage.setImageDrawable(null)
        }
        backgroundPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val bitmap = decodeScaledBitmap(path, 1280)
                if (bitmap != null) {
                    chatScroll.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
                }
            }
        }
    }

    private fun decodeScaledBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
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
        currentAssistantView = (chatColumn.getChildAt(chatColumn.childCount - 1) as? LinearLayout)?.getChildAt(0) as? TextView
        input.isEnabled = false
        sendButton.isEnabled = false
        status.text = "Bot sedang mengetik..."
        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Re-apply the selected character's identity immediately before every generation.
                // This prevents a stale prompt from the previously selected bot leaking into the chat.
                engine.setSystemPrompt(buildCharacterSystemPrompt(activeBot()))
                engine.sendUserPrompt(text, predictLength = 256).collect { token ->
                    assistant.text += token
                    withContext(Dispatchers.Main) {
                        currentAssistantView?.text = assistant.text
                        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                if (assistant.text.isBlank()) error("Model tidak menghasilkan token jawaban.")
                saveMessages()
                withContext(Dispatchers.Main) {
                    status.text = "Model aktif: ${activeModelName}"
                }
            } catch (e: Exception) {
                assistant.text = if (assistant.text.isBlank()) "Gagal menghasilkan jawaban: ${e.message}" else assistant.text
                saveMessages()
                withContext(Dispatchers.Main) {
                    currentAssistantView?.text = assistant.text
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

    private fun renderChatHistory() {
        header.text = "Chats"
        addSectionTitle("Riwayat percakapan", "Semua obrolan tersimpan di perangkat ini.")
        val p = palettes[themeIndex]
        val historyBots = bots.filter { readMessagesForBot(it.id).isNotEmpty() }
            .sortedByDescending { it.id == activeBotId }
        if (historyBots.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28))
                background = panelBackground(p.panel, Color.rgb(53, 66, 88), 18)
            }
            empty.addView(TextView(this).apply {
                text = "▤"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(p.accent)
            })
            empty.addView(TextView(this).apply {
                text = "Belum ada percakapan"; textSize = 17f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(p.text); setPadding(0, dp(8), 0, dp(4))
            })
            empty.addView(TextView(this).apply {
                text = "Pilih karakter di Discover untuk memulai obrolan pertamamu."
                textSize = 13f; gravity = Gravity.CENTER; setTextColor(p.muted)
            })
            chatColumn.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        } else {
            historyBots.forEach { bot ->
                val history = readMessagesForBot(bot.id)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = panelBackground(p.panel, if (bot.id == activeBotId) p.accent else Color.rgb(53, 66, 88), 18)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        saveMessages()
                        activeBotId = bot.id
                        prefs().edit().putString(KEY_ACTIVE_BOT, activeBotId).apply()
                        loadMessagesForBot(activeBotId)
                        if (engineReady && activeModelName != null) lifecycleScope.launch {
                            try { engine.setSystemPrompt(buildCharacterSystemPrompt(bot)) } catch (_: Exception) {}
                        }
                        currentPage = "conversation"
                        renderPage()
                    }
                }
                val avatar = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = panelBackground(p.background, p.muted, 14)
                    bot.avatarPath?.let { path -> if (File(path).exists()) decodeScaledBitmap(path, 192)?.let { setImageBitmap(it) } }
                }
                card.addView(avatar)
                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                details.addView(TextView(this).apply {
                    text = bot.name; textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(p.text)
                })
                details.addView(TextView(this).apply {
                    text = history.lastOrNull()?.text?.takeIf { it.isNotBlank() } ?: "Percakapan dimulai"
                    textSize = 12f; setTextColor(p.muted); maxLines = 2
                    setPadding(0, dp(4), 0, dp(6))
                })
                details.addView(TextView(this).apply {
                    text = "BUKA CHAT  ›"; textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(p.accent)
                })
                card.addView(details)
                chatColumn.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
        }
        addAction(chatColumn, "Temukan karakter lain") { currentPage = "discover"; renderPage() }
    }

    private fun renderMessages() {
        if (!::chatColumn.isInitialized) return
        chatColumn.removeAllViews()
        val p = palettes[themeIndex]
        if (messages.isEmpty()) {
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28))
                background = panelBackground(p.background, Color.rgb(53, 66, 88), 18)
            }
            welcome.addView(TextView(this).apply {
                text = "✦"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(p.accent)
            })
            welcome.addView(TextView(this).apply {
                text = "Mulai percakapan dengan ${activeBot().name}"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(p.text)
                setPadding(0, dp(8), 0, dp(4))
            })
            welcome.addView(TextView(this).apply {
                text = activeBot().greeting.ifBlank { "Tulis pesan pertamamu di bawah." }
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(p.muted)
            })
            chatColumn.addView(welcome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        } else {
            messages.forEach { message ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (message.role == "user") Gravity.END else Gravity.START
                }
                val bubble = TextView(this).apply {
                    text = message.text
                    textSize = 15f
                    setTextColor(p.text)
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                    background = panelBackground(if (message.role == "user") p.userBubble else p.botBubble, if (message.role == "user") p.accent else Color.rgb(53, 66, 88), 16)
                }
                row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(9)
                chatColumn.addView(row, lp)
            }
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun panelBackground(fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun loadLocalState() {
        themeIndex = prefs().getInt(KEY_THEME, 0).coerceIn(0, palettes.lastIndex)
        activeModelName = prefs().getString(KEY_ACTIVE_MODEL, null)
        activeBotId = prefs().getString(KEY_ACTIVE_BOT, "default") ?: "default"
        profilePath = prefs().getString(KEY_PROFILE, null)
        userName = prefs().getString(KEY_USER_NAME, "You") ?: "You"
        userHandle = prefs().getString(KEY_USER_HANDLE, "") ?: ""
        userBio = prefs().getString(KEY_USER_BIO, "") ?: ""
        userPersona = prefs().getString(KEY_USER_PERSONA, "") ?: ""
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
                bots.add(BotProfile(
                    item.optString("id"), item.optString("name"), item.optString("prompt"),
                    item.optString("avatar").takeIf { it.isNotBlank() },
                    item.optString("tagline"), item.optString("description"),
                    item.optString("greeting"), item.optString("example")
                ))
            }
        } catch (_: Exception) {}
        if (bots.none { it.id == "default" }) bots.add(0, BotProfile("default", "Sandev", ""))
        val perBotKey = KEY_MESSAGES_PREFIX + activeBotId
        if (prefs().contains(perBotKey)) {
            loadMessagesForBot(activeBotId)
        } else {
            // Migrate the older single-history format into the currently selected character.
            saveMessages()
        }
    }

    private fun saveMessages() {
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("role", it.role).put("text", it.text)) }
        val serialized = array.toString()
        prefs().edit()
            .putString(KEY_MESSAGES, serialized)
            .putString(KEY_MESSAGES_PREFIX + activeBotId, serialized)
            .apply()
    }

    private fun readMessagesForBot(botId: String): List<ChatMessage> {
        val raw = prefs().getString(KEY_MESSAGES_PREFIX + botId, "[]") ?: "[]"
        val result = mutableListOf<ChatMessage>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                result.add(ChatMessage(item.optString("role"), item.optString("text")))
            }
        } catch (_: Exception) {}
        return result
    }

    private fun loadMessagesForBot(botId: String) {
        messages.clear()
        messages.addAll(readMessagesForBot(botId))
    }

    private fun saveBots() {
        val array = JSONArray()
        bots.forEach { array.put(JSONObject()
            .put("id", it.id).put("name", it.name).put("prompt", it.systemPrompt)
            .put("avatar", it.avatarPath ?: "").put("tagline", it.tagline)
            .put("description", it.description).put("greeting", it.greeting)
            .put("example", it.exampleDialog)) }
        prefs().edit().putString(KEY_BOTS, array.toString()).apply()
    }

    private fun activeBot(): BotProfile = bots.firstOrNull { it.id == activeBotId } ?: bots.first()

    private fun buildCharacterSystemPrompt(bot: BotProfile): String = buildString {
        appendLine("IDENTITAS KARAKTER — INI WAJIB KONSISTEN")
        appendLine("Nama karakter kamu adalah: ${bot.name}.")
        if (bot.tagline.isNotBlank()) appendLine("Ciri singkat karakter: ${bot.tagline}.")
        if (bot.description.isNotBlank()) appendLine("Latar dan deskripsi karakter: ${bot.description}")
        appendLine()
        appendLine("KEPRIBADIAN DAN INSTRUKSI PEMBUAT")
        appendLine(bot.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT })
        if (bot.greeting.isNotBlank()) {
            appendLine()
            appendLine("SAPAAN PEMBUKA KARAKTER (sudah ditampilkan sebagai pesan awal; jangan mengulanginya tanpa alasan):")
            appendLine(bot.greeting)
        }
        if (bot.exampleDialog.isNotBlank()) {
            appendLine()
            appendLine("CONTOH GAYA DIALOG; tiru nada dan karakter, bukan menyalin secara otomatis:")
            appendLine(bot.exampleDialog)
        }
        appendLine()
        appendLine("ATURAN PERCAKAPAN")
        appendLine("- Tetap berbicara dan bertindak sesuai nama, kepribadian, latar, dan hubungan yang ditentukan pembuat.")
        appendLine("- Jangan mengganti identitasmu dengan Gemini, Google, ChatGPT, atau layanan/model lain.")
        appendLine("- Jika ditanya siapa dirimu, perkenalkan diri sebagai ${bot.name} sesuai karakter, bukan mengarang identitas layanan AI lain.")
        appendLine("- Jangan mengulang sapaan pertama di setiap balasan. Tanggapi isi pesan terbaru secara alami.")
        appendLine("- Jaga kesinambungan fakta dan hubungan dalam riwayat chat karakter ini. Jangan mengarang bahwa kamu mengetahui hal yang belum pernah diceritakan.")
        appendLine("- Gunakan bahasa yang dipakai pengguna, kecuali pembuat karakter menentukan bahasa lain.")
    }.trim()

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
            try {
                when (engine.state.value) {
                    is InferenceEngine.State.ModelReady,
                    is InferenceEngine.State.Error -> engine.cleanUp()
                    else -> Unit
                }
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private data class ChatMessage(val role: String, var text: String)
    private data class BotProfile(
        val id: String, val name: String, val systemPrompt: String,
        val avatarPath: String? = null, val tagline: String = "",
        val description: String = "", val greeting: String = "", val exampleDialog: String = ""
    )
    private data class Palette(
        val background: Int, val panel: Int, val text: Int, val muted: Int,
        val accent: Int, val userBubble: Int, val botBubble: Int
    )

    companion object {
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_AUTO_LOAD_MODEL = "auto_load_model"
        private const val KEY_ACTIVE_BOT = "active_bot"
        private const val KEY_THEME = "theme"
        private const val KEY_PROFILE = "profile"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_HANDLE = "user_handle"
        private const val KEY_USER_BIO = "user_bio"
        private const val KEY_USER_PERSONA = "user_persona"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_MESSAGES_PREFIX = "messages_bot_"
        private const val KEY_BOTS = "bots"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are Sandev, a helpful AI assistant. Answer clearly in the user's language."
        private val palettes = listOf(
            Palette(Color.rgb(10,16,29), Color.rgb(20,29,47), Color.rgb(242,247,255), Color.rgb(151,166,190), Color.rgb(55,207,239), Color.rgb(29,54,80), Color.rgb(25,37,57)),
            Palette(Color.rgb(248,249,252), Color.rgb(255,255,255), Color.rgb(29,33,42), Color.rgb(105,112,124), Color.rgb(48,110,230), Color.rgb(220,234,255), Color.rgb(236,239,245)),
            Palette(Color.rgb(16,28,35), Color.rgb(26,43,52), Color.rgb(236,247,250), Color.rgb(151,180,190), Color.rgb(32,190,170), Color.rgb(31,76,75), Color.rgb(33,55,67)),
            Palette(Color.rgb(30,18,35), Color.rgb(47,30,54), Color.rgb(250,241,255), Color.rgb(192,165,203), Color.rgb(221,112,255), Color.rgb(81,42,91), Color.rgb(59,39,69))
        )
    }
}
