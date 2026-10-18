package com.sandev.chat

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
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
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.delay
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
    private lateinit var chatMenuButton: Button
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
    private var activeTuneId = "default"
    private var manualTune = SamplingTune("manual", "Manual", 0.7f, 0.9f, 40, 1.1f, 192)
    private val samplingPresets = listOf(
        SamplingTune("qwen", "Qwen Tune", 0.7f, 0.8f, 20, 1.05f, 256),
        SamplingTune("gemma", "Gemma Tune", 1.0f, 0.95f, 64, 1.05f, 192),
        SamplingTune("smollm", "SmolLM Tune", 0.7f, 0.9f, 50, 1.15f, 160),
        SamplingTune("luminus", "Luminus Tune", 0.85f, 0.9f, 40, 1.10f, 320),
        SamplingTune("other", "Other Model Tune", 0.75f, 0.9f, 40, 1.10f, 192),
        SamplingTune("default", "Default", 0.3f, 0.95f, 40, 1.0f, 320)
    )
    private var pendingModelFile: File? = null
    private var themeIndex = 0
    private var profilePath: String? = null
    private var backgroundPath: String? = null
    private var currentAssistantView: TextView? = null
    private var currentPage = "discover"
    private var blackjackChips = 100
    private var blackjackDeck = mutableListOf<BlackjackCard>()
    private var blackjackPlayerHand = mutableListOf<BlackjackCard>()
    private var blackjackDealerHand = mutableListOf<BlackjackCard>()
    private var blackjackRoundActive = false
    private var blackjackMessage = "Undang bot AI untuk mulai bermain."
    private var blackjackBotInvited = false
    private var blackjackBotBusy = false
    private var blackjackPlayerTurn = true
    private var blackjackGameOver = false
    private var blackjackBotName = "Naya"
    private var blackjackBubbleText = ""
    private var blackjackBubbleJob: Job? = null
    private var blackjackAiJob: Job? = null
    private val blackjackChat = mutableListOf<BlackjackChatMessage>()
    private var editingBotId: String? = null
    private var conversationReturnPage = "discover"
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentPage) {
                    "conversation" -> {
                        input.clearFocus()
                        currentPage = conversationReturnPage
                        renderPage()
                    }
                    "blackjack" -> leaveBlackjackGame()
                    "discover" -> finish()
                    else -> {
                        currentPage = "discover"
                        renderPage()
                    }
                }
            }
        })
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
        chatMenuButton = Button(this).apply {
            text = "⋮"
            textSize = 20f
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "Aksi percakapan"
            visibility = View.GONE
            setOnClickListener { showChatActions() }
        }
        top.addView(chatMenuButton)
        root.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addAction(actions, "AI Model") { showModelMenu() }
        addAction(actions, "Model Tune") { showModelTuneMenu() }
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
                "Discover" -> currentPage == "discover" || currentPage == "blackjack"
                "Chats" -> currentPage == "chats" || currentPage == "conversation"
                "Create Bot" -> currentPage == "create_bot"
                "Music" -> currentPage == "music"
                "Profile" -> currentPage == "profile"
                else -> false
            }
            (item.getChildAt(0) as? TextView)?.setTextColor(if (selected) p.accent else p.muted)
            (item.getChildAt(1) as? TextView)?.setTextColor(if (selected) p.accent else p.text)
            item.background = if (selected) panelBackground(p.panel, p.accent, 12) else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
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
        chatMenuButton.visibility = if (inConversation) View.VISIBLE else View.GONE
        // Insets on the root reserve the real IME height, keeping the composer above the keyboard.
        composer.visibility = if (inConversation) View.VISIBLE else View.GONE
        navRow.visibility = if (inConversation) View.GONE else View.VISIBLE
        chatColumn.removeAllViews()
        when (currentPage) {
            "discover" -> renderDiscover()
            "chats" -> renderChatHistory()
            "conversation" -> { header.text = activeBot().name; renderMessages() }
            "model_tune" -> renderModelTunePage()
            "music" -> renderMusicPage()
            "blackjack" -> renderBlackjackPage()
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

        val gameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelBackground(p.panel, Color.rgb(48, 145, 104), 18)
        }
        gameCard.addView(TextView(this).apply {
            text = "♠  BLACKJACK"; textSize = 12f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(68, 205, 145))
        })
        gameCard.addView(TextView(this).apply {
            text = "Main kartu bareng Sandev"; textSize = 18f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(p.text)
            setPadding(0, dp(5), 0, dp(4))
        })
        gameCard.addView(TextView(this).apply {
            text = "Main live dengan model AI lokal • satu bot • bubble chat"
            textSize = 12f; setTextColor(p.muted)
        })
        addAction(gameCard, "Main Blackjack") {
            currentPage = "blackjack"
            if (!blackjackRoundActive && blackjackPlayerHand.isEmpty()) blackjackMessage = "Tekan Mulai untuk bermain."
            renderPage()
        }
        chatColumn.addView(gameCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })

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
                    setOnClickListener { openBotConversation(bot, addGreeting = true) }
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
                if (bot.id != "default") {
                    val manageBotButton = Button(this).apply {
                        text = "⋮"
                        textSize = 18f
                        minWidth = 0
                        setPadding(dp(6), 0, dp(6), 0)
                        contentDescription = "Kelola bot " + bot.name
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(bot.name)
                                .setItems(arrayOf("Edit bot", "Hapus bot")) { _, action ->
                                    if (action == 0) {
                                        editingBotId = bot.id
                                        currentPage = "create_bot"
                                        renderPage()
                                    } else {
                                        showDeleteBotConfirmation(bot)
                                    }
                                }.show()
                        }
                    }
                    card.addView(manageBotButton, LinearLayout.LayoutParams(dp(42), dp(48)))
                }
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

    private fun renderBlackjackPage() {
        header.text = "Blackjack • Naya"
        val p = palettes[themeIndex]
        addSectionTitle("Main bareng Naya", "Teman Blackjack khusus: ceria, jahil, dan suka bercanda. Model AI tetap memilih langkahnya sendiri.")
        if (!blackjackBotInvited) {
            val inviteCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
                background = panelBackground(p.panel, Color.rgb(48, 145, 104), 18)
            }
            inviteCard.addView(TextView(this).apply {
                text = "♠ MEJA BLACKJACK"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(68, 205, 145))
            })
            inviteCard.addView(TextView(this).apply {
                text = "Naya, teman mainmu 🎴"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.text); setPadding(0, dp(8), 0, dp(4))
            })
            inviteCard.addView(TextView(this).apply {
                text = if (activeModelName != null && engineReady) "Model siap: " + activeModelName else "Pilih atau impor model GGUF agar Naya bisa benar-benar bermain dengan AI."
                textSize = 13f; setTextColor(p.muted)
            })
            addAction(inviteCard, "Main bareng Naya") { inviteBlackjackBot() }
            chatColumn.addView(inviteCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            addAction(chatColumn, "Kembali ke Discover") { currentPage = "discover"; renderPage() }
            return
        }

        val characterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelBackground(p.panel, p.muted, 18)
        }
        characterRow.addView(buildBlackjackNayaAvatar(58), LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(10) })
        val characterInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Naya ✨"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.text)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Pemain AI • ceria, jahil, suka bercanda"; textSize = 12f; setTextColor(p.muted)
            })
            if (blackjackBubbleText.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = blackjackBubbleText; textSize = 13f; setTextColor(p.text)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = panelBackground(p.botBubble, p.accent, 14)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
            }
        }
        characterRow.addView(characterInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chatColumn.addView(characterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val scorePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelBackground(p.panel, Color.rgb(53, 66, 88), 16)
        }
        scorePanel.addView(TextView(this).apply {
            text = "🏆 SKOR PERTANDINGAN"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.accent); setPadding(0, 0, 0, dp(8))
        })
        val scoreRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val playerScore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = userName.ifBlank { "Kamu" }; textSize = 12f; setTextColor(p.muted) })
            addView(TextView(this@MainActivity).apply {
                text = if (blackjackPlayerHand.isEmpty()) "—" else handValue(blackjackPlayerHand).toString()
                textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.text)
            })
        }
        val versus = TextView(this).apply {
            text = "VS"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.muted); gravity = Gravity.CENTER
        }
        val botScore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(TextView(this@MainActivity).apply { text = "Naya"; textSize = 12f; setTextColor(p.muted) })
            addView(TextView(this@MainActivity).apply {
                text = if (blackjackDealerHand.isEmpty()) "—" else if (blackjackRoundActive && !blackjackGameOver) "?" else handValue(blackjackDealerHand).toString()
                textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.text)
            })
        }
        scoreRow.addView(playerScore, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scoreRow.addView(versus, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        scoreRow.addView(botScore, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scorePanel.addView(scoreRow)
        scorePanel.addView(TextView(this).apply {
            text = "Chip: " + blackjackChips; textSize = 12f; setTextColor(p.muted); setPadding(0, dp(8), 0, 0)
        })
        chatColumn.addView(scorePanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat(); setColor(Color.rgb(13, 93, 65))
                setStroke(dp(1), Color.rgb(49, 164, 113))
            }
        }
        table.addView(TextView(this).apply {
            text = "MEJA BLACKJACK"; textSize = 12f; letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(190, 239, 213))
        })
        table.addView(TextView(this).apply {
            text = "Naya • PEMAIN AI"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); setPadding(0, dp(10), 0, dp(6))
        })
        table.addView(buildCardRow(blackjackDealerHand, hideAll = blackjackRoundActive && !blackjackGameOver))
        table.addView(TextView(this).apply {
            text = if (blackjackDealerHand.isEmpty()) "Menunggu ronde" else if (blackjackRoundActive && !blackjackGameOver) "Kartu Naya dirahasiakan" else "Nilai Naya: " + handValue(blackjackDealerHand)
            textSize = 12f; setTextColor(Color.rgb(220, 247, 232)); setPadding(0, dp(5), 0, dp(16))
        })
        table.addView(TextView(this).apply {
            text = userName.ifBlank { "Kamu" } + " • PEMAIN"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(6))
        })
        table.addView(buildCardRow(blackjackPlayerHand))
        table.addView(TextView(this).apply {
            text = if (blackjackPlayerHand.isEmpty()) "Nilai kartu: —" else "Nilai kartu: " + handValue(blackjackPlayerHand)
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(4))
        })
        table.addView(TextView(this).apply {
            text = blackjackMessage; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(dp(6), dp(8), dp(6), dp(4))
        })
        chatColumn.addView(table, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val chatCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelBackground(p.panel, p.muted, 16)
        }
        chatCard.addView(TextView(this).apply {
            text = "💬 Chat ke Naya"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text); setPadding(0, 0, 0, dp(8))
        })
        val chatActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val chatInput = EditText(this).apply {
            hint = "Ketik pesan…"; setSingleLine(true); maxLines = 1; imeOptions = EditorInfo.IME_ACTION_SEND
            textSize = 14f; setTextColor(p.text); setHintTextColor(p.muted); setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelBackground(p.background, p.muted, 12)
        }
        chatActions.addView(chatInput, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        addAction(chatActions, if (blackjackBotBusy) "Tunggu…" else "Kirim") { sendBlackjackChat(chatInput.text.toString()) }
        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendBlackjackChat(chatInput.text.toString()); true } else false
        }
        chatCard.addView(chatActions)
        chatColumn.addView(chatCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addAction(actions, if (!blackjackRoundActive) "Mulai ronde" else "Draw 1 kartu") {
            if (blackjackBotBusy) toast("Tunggu Naya selesai berpikir dulu.")
            else if (!blackjackRoundActive) startBlackjackRound()
            else if (blackjackPlayerTurn) playerDrawBlackjackCard()
            else toast("Sekarang giliran Naya.")
        }
        addAction(actions, "Stand / Selesai giliran") {
            if (blackjackBotBusy) toast("Tunggu Naya selesai berpikir dulu.")
            else if (blackjackRoundActive && blackjackPlayerTurn) runBlackjackBotTurn()
            else if (!blackjackRoundActive) toast("Mulai ronde baru dulu.")
            else toast("Naya sedang mendapat giliran.")
        }
        chatColumn.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        addAction(chatColumn, "Keluar ke Discover") { leaveBlackjackGame() }
        addAction(chatColumn, "Aturan singkat") {
            AlertDialog.Builder(this).setTitle("Blackjack vs Naya")
                .setMessage("Naya adalah karakter khusus Blackjack dan tidak muncul di daftar bot. Pesanmu dikirim melalui satu baris chat; komentar Naya muncul sebagai bubble sementara di samping avatar. Skor dan chip ditampilkan di panel tersendiri. Model AI memilih HIT atau STAND, sedangkan host membagikan kartu, memvalidasi giliran, dan menghitung hasil.")
                .setPositiveButton("Mengerti", null).show()
        }
    }

    private fun buildBlackjackNayaAvatar(sizeDp: Int): TextView = TextView(this).apply {
        text = "😄"; textSize = 28f; gravity = Gravity.CENTER
        contentDescription = "Avatar Naya"
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.rgb(255, 205, 128)); setStroke(dp(2), Color.rgb(255, 151, 76))
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun showBlackjackBubble(message: String) {
        val cleanMessage = message.trim().take(220)
        if (cleanMessage.isBlank()) return
        blackjackBubbleJob?.cancel()
        blackjackBubbleText = cleanMessage
        if (currentPage == "blackjack") renderPage()
        blackjackBubbleJob = lifecycleScope.launch {
            delay(5000)
            if (blackjackBubbleText == cleanMessage) {
                blackjackBubbleText = ""
                if (currentPage == "blackjack") renderPage()
            }
        }
    }

    private fun buildCardRow(cards: List<BlackjackCard>, hideSecond: Boolean = false, hideAll: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        if (cards.isEmpty()) {
            row.addView(TextView(this).apply {
                text = "—"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(54), dp(76)))
        } else {
            cards.forEachIndexed { index, card ->
                val hidden = hideAll || (hideSecond && index == 1)
                val red = card.suit == "♥" || card.suit == "♦"
                val cardView = TextView(this).apply {
                    text = if (hidden) "?" else card.display
                    textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    setTextColor(if (hidden) Color.WHITE else if (red) Color.rgb(190, 35, 48) else Color.rgb(25, 32, 43))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(9).toFloat()
                        setColor(if (hidden) Color.rgb(32, 69, 113) else Color.WHITE)
                        setStroke(dp(1), if (hidden) Color.rgb(121, 174, 228) else Color.rgb(220, 225, 232))
                    }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }
                val params = LinearLayout.LayoutParams(dp(54), dp(76))
                if (index > 0) params.leftMargin = dp(5)
                row.addView(cardView, params)
            }
        }
        return row
    }

    private fun inviteBlackjackBot() {
        if (!engineReady) { toast("Engine AI belum siap."); return }
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress || blackjackBotBusy) {
            toast("Tunggu proses model yang sedang berjalan selesai."); return
        }
        val file = activeModelName?.let { modelFile(it) }
        if (file == null || !file.exists()) { toast("Pilih atau impor model GGUF terlebih dahulu."); return }
        blackjackMessage = "Naya sedang menyiapkan meja…"; renderPage()
        loadModelFile(file, false,
            onReady = {
                blackjackBotInvited = true
                blackjackBotName = "Naya"
                blackjackBubbleText = ""
                blackjackMessage = "Naya sudah siap. Tekan Mulai ronde."
                showBlackjackBubble("Yeay, aku Naya! Siap-siap ya, aku jago… kadang-kadang! 😂")
                renderPage()
            },
            onFailure = { reason -> blackjackMessage = "Naya gagal bergabung: " + reason; renderPage() },
            systemPromptOverride = blackjackSystemPrompt()
        )
    }

    private fun blackjackSystemPrompt(): String =
        "Namamu Naya. Kamu adalah karakter cewek dewasa yang ceria, jahil, hangat, suka bercanda, menggoda lawan dengan ramah, dan bereaksi ekspresif dalam bahasa Indonesia. " +
        "Kamu adalah pemain Blackjack sungguhan melawan pengguna, bukan dealer dan bukan narator. Kamu hanya muncul di game Blackjack, bukan bot umum. " +
        "Host membagikan kartu, menyembunyikan kartu privatmu saat ronde aktif, memvalidasi giliran, dan menghitung hasil. Jangan pernah mengarang kartu atau mengubah skor. " +
        "Saat MODE: PILIH LANGKAH GAME, nilai kartu privat dan kartu pengguna diberikan di prompt. Pilih aksi berdasarkan kartu privatmu dan target Blackjack 21. Jawab tepat dua baris: ACTION: HIT atau ACTION: STAND; COMMENT: satu komentar singkat, ceria, jahil, alami dalam bahasa Indonesia. " +
        "Saat MODE: CHAT ONLY, jangan mengambil kartu, jangan memilih aksi game, dan jangan mengubah ronde. Jawab tepat dua baris: ACTION: WAIT; COMMENT: balasan alami terhadap pesan pengguna, dengan kepribadian Naya. " +
        "Jangan ungkapkan kartu privatmu kepada pengguna. Jangan mengaku tidak bisa berkomentar saat sesi berjalan; selalu berikan komentar singkat yang relevan. Jangan menulis penjelasan di luar format yang diminta."

    private fun startBlackjackRound() {
        if (!blackjackBotInvited || blackjackBotBusy) return
        if (blackjackChips < 10) blackjackChips = 100
        blackjackChips -= 10
        blackjackDeck = (1..13).flatMap { rank -> listOf("♠", "♥", "♦", "♣").map { suit -> BlackjackCard(rank, suit) } }.shuffled().toMutableList()
        blackjackPlayerHand = mutableListOf()
        blackjackDealerHand = mutableListOf()
        blackjackPlayerHand.add(drawBlackjackCard())
        blackjackDealerHand.add(drawBlackjackCard())
        blackjackPlayerHand.add(drawBlackjackCard())
        blackjackDealerHand.add(drawBlackjackCard())
        blackjackRoundActive = true; blackjackPlayerTurn = true; blackjackGameOver = false
        blackjackMessage = "Giliranmu. Draw satu kartu atau Stand untuk menyerahkan giliran ke Naya."
        showBlackjackBubble("Kartu dibagikan! Ayo kita lihat siapa yang lebih beruntung. 😜")
        if (handValue(blackjackPlayerHand) == 21) runBlackjackBotTurn() else renderPage()
    }

    private fun playerDrawBlackjackCard() {
        if (!blackjackRoundActive || !blackjackPlayerTurn || blackjackBotBusy) return
        val card = drawBlackjackCard()
        blackjackPlayerHand.add(card)
        val score = handValue(blackjackPlayerHand)
        when {
            score > 21 -> {
                finishBlackjackRound("Bust! Nilaimu " + score + ". Naya menang.")
                showBlackjackBubble("Yahhh, bust! Aku menang… eh, jangan marah ya, cuma bercanda! 😂")
                renderPage()
            }
            score == 21 -> {
                blackjackMessage = "Kamu mencapai 21. Giliran Naya."
                showBlackjackBubble("Wihhh, langsung 21?! Aku mulai gugup nih! 😳")
                renderPage()
                runBlackjackBotTurn()
            }
            else -> {
                blackjackMessage = "Kartu masuk: " + card.display + ". Naya sedang bereaksi…"
                renderPage()
                requestBlackjackComment("Pengguna baru mengambil " + card.display + " dan total kartunya sekarang " + score + ". Beri komentar singkat sebagai lawan yang sedang menunggu giliran. Jangan memilih aksi atau mengubah game.")
            }
        }
    }

    private fun runBlackjackBotTurn() {
        if (!blackjackBotInvited || !blackjackRoundActive || blackjackBotBusy) return
        if (!engineReady || activeModelName == null) { blackjackMessage = "Model AI tidak siap."; renderPage(); return }
        blackjackPlayerTurn = false
        blackjackBotBusy = true
        blackjackMessage = "Naya sedang mempertimbangkan langkahnya…"
        renderPage()
        blackjackAiJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                var decisions = 0
                var finished = false
                while (!finished && decisions++ < 12) {
                    val prompt = withContext(Dispatchers.Main) { buildBlackjackDecisionPrompt() }
                    var output = ""
                    var timedOut = false

                    // Some small GGUF models can finish with no usable text, or stall during
                    // generation. Bound each request so the Blackjack turn cannot stay busy forever.
                    try {
                        val raw = StringBuilder()
                        withTimeout(45_000L) {
                            engine.sendUserPrompt(prompt, predictLength = 96).collect { raw.append(it) }
                        }
                        output = raw.toString()
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        timedOut = true
                    }

                    var action = Regex("(?im)^\\s*ACTION\\s*:\\s*(HIT|STAND)\\b").find(output)?.groupValues?.get(1)?.uppercase()
                    var comment = Regex("(?im)^\\s*COMMENT\\s*:\\s*(.+)$").find(output)?.groupValues?.get(1)?.trim().orEmpty()

                    // Retry malformed/empty output once. Don't start a second inference after a timeout,
                    // because the native inference engine may still be unwinding the cancelled request.
                    if (action == null && !timedOut) {
                        try {
                            val retry = StringBuilder()
                            withTimeout(30_000L) {
                                engine.sendUserPrompt(
                                    "Pilih satu aksi Blackjack sekarang berdasarkan keadaan game yang diberikan sebelumnya. " +
                                        "Jawab tepat dua baris dan jangan beri penjelasan tambahan:\n" +
                                        "ACTION: HIT atau ACTION: STAND\nCOMMENT: satu komentar pendek dalam bahasa Indonesia.",
                                    predictLength = 48
                                ).collect { retry.append(it) }
                            }
                            output = retry.toString()
                            action = Regex("(?im)^\\s*ACTION\\s*:\\s*(HIT|STAND)\\b").find(output)?.groupValues?.get(1)?.uppercase()
                            comment = Regex("(?im)^\\s*COMMENT\\s*:\\s*(.+)$").find(output)?.groupValues?.get(1)?.trim().orEmpty()
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            timedOut = true
                        }
                    }

                    // Last-resort game rule only: keep Gemma's own action/comment whenever valid,
                    // but never let an empty or malformed model response freeze the round.
                    if (action == null) {
                        val total = withContext(Dispatchers.Main) { handValue(blackjackDealerHand) }
                        action = if (total < 17) "HIT" else "STAND"
                        comment = when {
                            timedOut -> if (action == "HIT") "Aduh, aku sempat nge-lag! Ambil kartu dulu, ya 😅" else "Hmm, aku berhenti di sini dulu. Jangan senang dulu! 😏"
                            action == "HIT" -> "Aku masih berani ambil satu kartu lagi! 😜"
                            else -> "Aku cukup puas dengan kartuku. Sekarang kita lihat hasilnya! 😏"
                        }
                        withContext(Dispatchers.Main) {
                            blackjackMessage = if (timedOut) {
                                "Respons Gemma terlalu lama; Naya memakai langkah cadangan agar ronde tetap berjalan."
                            } else {
                                "Respons Gemma tidak terbaca; Naya memakai langkah cadangan agar ronde tetap berjalan."
                            }
                        }
                    }

                    val result = withContext(Dispatchers.Main) {
                        if (comment.isNotBlank()) showBlackjackBubble(comment.take(220))
                        if (action == "HIT" && handValue(blackjackDealerHand) < 21) {
                            val card = drawBlackjackCard()
                            blackjackDealerHand.add(card)
                            val score = handValue(blackjackDealerHand)
                            if (score > 21) {
                                finishBlackjackRound("Naya bust di " + score + ". Kamu menang!")
                                showBlackjackBubble(if (comment.isBlank()) "Aduhhh, bust! Kamu menang! 😭😂" else comment + "\nAduhhh, aku bust! Kamu menang! 😭😂")
                                renderPage()
                                "DONE"
                            } else if (score == 21) {
                                settleBlackjackRound()
                                renderPage()
                                "DONE"
                            } else {
                                blackjackMessage = "Naya mengambil satu kartu dan berpikir lagi…"
                                renderPage()
                                "CONTINUE"
                            }
                        } else {
                            settleBlackjackRound()
                            renderPage()
                            "DONE"
                        }
                    }
                    if (result == "DONE") finished = true
                }
                if (!finished) withContext(Dispatchers.Main) {
                    blackjackMessage = "Batas keputusan Naya tercapai; ronde diselesaikan dengan aman."
                    settleBlackjackRound()
                    renderPage()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    blackjackMessage = "AI mengalami kendala: " + (e.message ?: e.javaClass.simpleName)
                    showBlackjackBubble("Ups, aku sempat tersendat. Kita tetap lanjut, ya! 😅")
                    // If inference fails, restore a usable turn state instead of leaving the player
                    // locked out. The user can tap Stand or Draw again.
                    blackjackPlayerTurn = true
                    renderPage()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    blackjackBotBusy = false
                    renderPage()
                }
            }
        }
    }

    private fun buildBlackjackDecisionPrompt(): String = buildString {
        appendLine("MODE: PILIH LANGKAH GAME")
        appendLine("Giliranmu. Kartu tanganmu (privat): " + blackjackDealerHand.joinToString(", ") { it.display })
        appendLine("Nilai tanganmu: " + handValue(blackjackDealerHand))
        appendLine("Kartu pengguna yang terlihat: " + blackjackPlayerHand.joinToString(", ") { it.display })
        appendLine("Nilai pengguna: " + handValue(blackjackPlayerHand))
        appendLine("Pilih HIT untuk mengambil tepat satu kartu atau STAND untuk berhenti. Target mendekati 21 tanpa melewati 21.")
        appendLine("Kamu adalah Naya: tetap ceria dan suka bercanda dalam komentar, tetapi pilih aksi berdasarkan kartu sebenarnya.")
        appendLine("Jawab tepat dua baris: ACTION: HIT atau ACTION: STAND; COMMENT: satu kalimat pendek.")
    }

    private fun requestBlackjackComment(prompt: String) {
        if (!blackjackBotInvited || blackjackBotBusy || !engineReady) return
        blackjackBotBusy = true; renderPage()
        blackjackAiJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val gamePrompt = prompt + "\nMODE: CHAT ONLY. Kamu adalah Naya, tetap ceria, jahil, dan alami. Balas pesan pengguna secara langsung. Jangan memilih langkah, jangan draw, jangan ubah skor atau ronde. Jawab tepat dua baris terpisah:\nACTION: WAIT\nCOMMENT: satu balasan singkat yang relevan dalam bahasa Indonesia."
                val output = StringBuilder()
                engine.sendUserPrompt(gamePrompt, predictLength = 80).collect { output.append(it) }
                val result = output.toString()
                val commentMatch = Regex("(?is)\\bCOMMENT\\s*:\\s*(.+)").find(result)
                val comment = commentMatch?.groupValues?.get(1)
                    ?.lineSequence()?.firstOrNull()?.trim()
                    ?.replace(Regex("<\\|[^>]+\\|>"), "")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: result.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("ACTION:", true) && !it.contains("MODE: CHAT ONLY", true) }
                        .lastOrNull()
                        ?.replace(Regex("<\\|[^>]+\\|>"), "")?.trim().orEmpty()
                withContext(Dispatchers.Main) {
                    if (comment.isNotBlank()) showBlackjackBubble(comment.take(220))
                    blackjackMessage = if (blackjackRoundActive && blackjackPlayerTurn) "Giliranmu. Draw satu kartu atau Stand untuk menyerahkan giliran." else if (blackjackRoundActive) "Ronde berlangsung. Naya akan mengambil keputusan saat gilirannya." else "Siap untuk ronde berikutnya."
                    renderPage()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showBlackjackBubble("Maaf, aku kepeleset sebentar 😅 Coba bilang lagi, ya!")
                    blackjackMessage = if (blackjackRoundActive) blackjackMessage else "Siap untuk ronde berikutnya."
                    renderPage()
                }
            } finally { withContext(Dispatchers.Main) { blackjackBotBusy = false; renderPage() } }
        }
    }

    private fun sendBlackjackChat(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isEmpty()) return
        if (!blackjackBotInvited) { toast("Mulai sesi Naya dulu."); return }
        if (blackjackBotBusy) { toast("Naya sedang berpikir. Tunggu sebentar, ya."); return }
        requestBlackjackComment("Pesan dari pemain: \"" + message + "\". Balas langsung kepada pemain sebagai Naya dengan satu komentar singkat yang ceria dan jahil.")
    }

    private fun leaveBlackjackGame() {
        blackjackAiJob?.cancel(); blackjackAiJob = null; blackjackBotBusy = false
        blackjackBubbleJob?.cancel(); blackjackBubbleJob = null; blackjackBubbleText = ""
        currentPage = "discover"
        val file = activeModelName?.let { modelFile(it) }
        if (blackjackBotInvited && file != null && file.exists() && engineReady) {
            blackjackBotInvited = false
            loadModelFile(file, false, onReady = { renderPage() }, onFailure = { renderPage(); toast("Gagal memulihkan prompt chat: " + it) })
        } else { blackjackBotInvited = false; renderPage() }
    }

    private fun drawBlackjackCard(): BlackjackCard {
        if (blackjackDeck.isEmpty()) {
            blackjackDeck = (1..13).flatMap { rank ->
                listOf("♠", "♥", "♦", "♣").map { suit -> BlackjackCard(rank, suit) }
            }.shuffled().toMutableList()
        }
        return blackjackDeck.removeAt(blackjackDeck.lastIndex)
    }

    private fun handValue(hand: List<BlackjackCard>): Int {
        var total = hand.sumOf { it.rankValue() }
        var aces = hand.count { it.rank == 1 }
        while (total > 21 && aces > 0) { total -= 10; aces-- }
        return total
    }

    private fun settleBlackjackRound() {
        if (!blackjackRoundActive) return
        val player = handValue(blackjackPlayerHand)
        val bot = handValue(blackjackDealerHand)
        when {
            player > 21 -> finishBlackjackRound("Bust! Nilaimu " + player + ". Naya menang.")
            bot > 21 -> { blackjackChips += 20; finishBlackjackRound("Naya bust di " + bot + ". Kamu menang +10 chip bersih!") }
            player > bot -> { blackjackChips += 20; finishBlackjackRound("Kamu menang " + player + " vs " + bot + "! +10 chip bersih.") }
            player < bot -> finishBlackjackRound("Naya menang " + bot + " vs " + player + ".")
            else -> { blackjackChips += 10; finishBlackjackRound("Seri " + player + "–" + bot + ". Taruhan dikembalikan.") }
        }
        showBlackjackBubble(when {
            player > 21 -> "Yahhh, aku menang ronde ini! Next round balas dendam, ya! 😜"
            bot > 21 -> "Aduhhh, aku bust! Oke, ronde ini punya kamu. 😂"
            player > bot -> "Hah?! Kamu menang! Oke, aku akui kamu jago… dikit! 🤣"
            player < bot -> "Yes! Naya menang! Jangan sedih, masih ada ronde berikutnya. 😎"
            else -> "Seri?! Kita sama-sama bikin deg-degan. Rematch? 😆"
        })
    }

    private fun finishBlackjackRound(message: String) {
        blackjackRoundActive = false; blackjackPlayerTurn = false; blackjackGameOver = true
        blackjackMessage = message
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
            "🎚  Model Tune (${activeTuneLabel()})",
            "⚙  Pengaturan model otomatis"
        )
        AlertDialog.Builder(this).setTitle("AI Model · Q4_K_M")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> modelPicker.launch(arrayOf("*/*"))
                    1 -> showSavedModels(modelFiles(), false)
                    2 -> showSavedModels(modelFiles(), true)
                    3 -> showModelTuneMenu()
                    4 -> showModelSettings()
                }
            }.show()
    }

    private fun showModelTuneMenu() {
        // Use a full in-app page instead of a compact dialog so every preset is
        // visibly listed on small screens and never hidden behind a truncated menu.
        currentPage = "model_tune"
        renderPage()
    }

    private fun renderModelTunePage() {
        header.text = "Model Tune"
        val p = palettes[themeIndex]
        addSectionTitle(
            "Pengaturan Model Tune",
            "Pilih sendiri preset yang ingin dipakai. Semua pilihan ditampilkan di halaman ini."
        )

        val descriptions = mapOf(
            "qwen" to "Preset untuk model Qwen.",
            "gemma" to "Preset untuk model Gemma.",
            "smollm" to "Preset untuk model SmolLM.",
            "luminus" to "Preset Luminus.",
            "other" to "Preset umum untuk model lain.",
            "default" to "Pengaturan standar bawaan."
        )

        samplingPresets.forEach { tune ->
            val selected = activeTuneId == tune.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = panelBackground(p.panel, if (selected) p.accent else p.muted, 16)
                isClickable = true
                isFocusable = true
                contentDescription = "${tune.label}${if (selected) ", sedang aktif" else ""}"
                setOnClickListener {
                    applySamplingTune(tune, tune.id)
                    renderPage()
                }
            }
            card.addView(TextView(this).apply {
                text = (if (selected) "✓  " else "") + tune.label
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selected) p.accent else p.text)
            })
            card.addView(TextView(this).apply {
                text = descriptions[tune.id] ?: "Preset sampling model."
                textSize = 12f
                setTextColor(p.muted)
                setPadding(0, dp(3), 0, dp(5))
            })
            card.addView(TextView(this).apply {
                text = "Temperature ${tune.temperature}  ·  Top-P ${tune.topP}  ·  Top-K ${tune.topK}"
                textSize = 12f
                setTextColor(p.text)
            })
            card.addView(TextView(this).apply {
                text = "Repetition Penalty ${tune.repeatPenalty}  ·  Max Tokens ${tune.maxTokens}"
                textSize = 12f
                setTextColor(p.text)
                setPadding(0, dp(2), 0, 0)
            })
            chatColumn.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        val manualSelected = activeTuneId == "manual"
        val manualCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelBackground(p.panel, if (manualSelected) p.accent else p.muted, 16)
            isClickable = true
            isFocusable = true
            contentDescription = "Manual Settings, atur parameter sendiri"
            setOnClickListener { showManualTuneDialog() }
        }
        manualCard.addView(TextView(this).apply {
            text = (if (manualSelected) "✓  " else "✎  ") + "Manual Settings"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (manualSelected) p.accent else p.text)
        })
        manualCard.addView(TextView(this).apply {
            text = "Atur Temperature, Top-P, Top-K, Repetition Penalty, dan Max Tokens sendiri."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(0, dp(3), 0, dp(5))
        })
        if (manualSelected) {
            manualCard.addView(TextView(this).apply {
                text = "Temperature ${manualTune.temperature}  ·  Top-P ${manualTune.topP}  ·  Top-K ${manualTune.topK}"
                textSize = 12f
                setTextColor(p.text)
            })
            manualCard.addView(TextView(this).apply {
                text = "Repetition Penalty ${manualTune.repeatPenalty}  ·  Max Tokens ${manualTune.maxTokens}"
                textSize = 12f
                setTextColor(p.text)
                setPadding(0, dp(2), 0, 0)
            })
        }
        chatColumn.addView(manualCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val note = TextView(this).apply {
            text = "Preset yang dipilih disimpan. Parameter sampling diterapkan saat model siap; Max Tokens membatasi panjang jawaban."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(dp(4), dp(2), dp(4), dp(12))
        }
        chatColumn.addView(note)
    }

    private fun showManualTuneDialog() {
        val initial = if (activeTuneId == "manual") manualTune else activeSamplingTune()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun addField(label: String, value: String, numericType: Int): EditText {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(0, dp(6), 0, dp(3))
            })
            return EditText(this).apply {
                setSingleLine(true)
                inputType = numericType
                setText(value)
                textSize = 16f
                panel.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            }
        }
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val temperature = addField("Temperature (0.05–2.0)", initial.temperature.toString(), decimal)
        val topP = addField("Top-p (0.10–1.0)", initial.topP.toString(), decimal)
        val topK = addField("Top-k (1–200)", initial.topK.toString(), InputType.TYPE_CLASS_NUMBER)
        val repeat = addField("Repetition penalty (1.0–2.0)", initial.repeatPenalty.toString(), decimal)
        val maxTokens = addField("Max output tokens (32–1024)", initial.maxTokens.toString(), InputType.TYPE_CLASS_NUMBER)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Manual Tune")
            .setMessage("Atur cara model memilih token. Nilai ini berlaku untuk model yang sedang dimuat.")
            .setView(panel)
            .setPositiveButton("Terapkan", null)
            .setNegativeButton("Batal", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val tempValue = temperature.text.toString().toFloat()
                    val topPValue = topP.text.toString().toFloat()
                    val topKValue = topK.text.toString().toInt()
                    val repeatValue = repeat.text.toString().toFloat()
                    val maxTokensValue = maxTokens.text.toString().toInt()
                    require(maxTokensValue in 32..1024) { "Max output tokens harus 32–1024." }
                    require(tempValue in 0.05f..2.0f) { "Temperature harus 0.05–2.0." }
                    require(topPValue in 0.10f..1.0f) { "Top-p harus 0.10–1.0." }
                    require(topKValue in 1..200) { "Top-k harus 1–200." }
                    require(repeatValue in 1.0f..2.0f) { "Repetition penalty harus 1.0–2.0." }
                    val tune = SamplingTune("manual", "Manual", tempValue, topPValue, topKValue, repeatValue, maxTokensValue)
                    manualTune = tune
                    applySamplingTune(tune, "manual")
                    dialog.dismiss()
                    if (currentPage == "model_tune") renderPage()
                } catch (e: Exception) {
                    toast(e.message ?: "Nilai tune tidak valid.")
                }
            }
        }
        dialog.show()
    }

    private fun activeSamplingTune(): SamplingTune =
        if (activeTuneId == "manual") manualTune
        else samplingPresets.firstOrNull { it.id == activeTuneId } ?: samplingPresets.last()

    private fun activeTuneLabel(): String = activeSamplingTune().label

    private fun applySamplingTune(tune: SamplingTune, selectedId: String) {
        activeTuneId = selectedId
        if (selectedId == "manual") manualTune = tune.copy(id = "manual", label = "Manual")
        val editor = prefs().edit()
            .putString(KEY_ACTIVE_TUNE, activeTuneId)
            .putFloat(KEY_MANUAL_TEMPERATURE, manualTune.temperature)
            .putFloat(KEY_MANUAL_TOP_P, manualTune.topP)
            .putInt(KEY_MANUAL_TOP_K, manualTune.topK)
            .putFloat(KEY_MANUAL_REPEAT_PENALTY, manualTune.repeatPenalty)
            .putInt(KEY_MANUAL_MAX_TOKENS, manualTune.maxTokens)
        editor.apply()
        if (::engine.isInitialized && engineReady && engine.state.value is InferenceEngine.State.ModelReady) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val active = activeSamplingTune()
                    engine.setSamplingParams(active.temperature, active.topP, active.topK, active.repeatPenalty)
                    withContext(Dispatchers.Main) {
                        status.text = "Tune aktif: ${active.label}"
                        toast("${active.label} diterapkan.")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("Tune tersimpan, tetapi gagal diterapkan: ${e.message}") }
                }
            }
        } else {
            toast("${tune.label} disimpan; akan diterapkan saat model dimuat.")
        }
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
        status.text = "Memeriksa file dan metadata GGUF..."
        sendButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Accept any GGUF quantization. Actual architecture/operator support is
                // determined by the bundled llama.cpp engine when loadModel() runs.
                contentResolver.openInputStream(uri)?.use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                } ?: error("File model tidak bisa dibaca atau bukan GGUF yang valid.")
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

    private fun loadModelFile(
        file: File,
        showToast: Boolean,
        historyOverride: List<ChatMessage>? = null,
        onReady: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
        systemPromptOverride: String? = null
    ) {
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
                engine.setSystemPrompt(systemPromptOverride ?: buildCharacterSystemPrompt(activeBot(), historyOverride ?: messages.toList()))
                val tune = activeSamplingTune()
                engine.setSamplingParams(tune.temperature, tune.topP, tune.topK, tune.repeatPenalty)
                withContext(Dispatchers.Main) {
                    activeModelName = file.name
                    prefs().edit().putString(KEY_ACTIVE_MODEL, file.name).apply()
                    status.text = "Model aktif: ${file.name}"
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    if (showToast) toast("Model siap digunakan.")
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activeModelName = null
                    prefs().edit().remove(KEY_ACTIVE_MODEL).apply()
                    val reason = e.message?.takeIf { it.isNotBlank() }
                        ?: "Error ${e.javaClass.simpleName.ifBlank { "tidak diketahui" }}"
                    status.text = "Model gagal dimuat: $reason"
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    onFailure?.invoke(reason)
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
                openBotConversation(list[index])
            }.show()
    }

    private fun openBotConversation(bot: BotProfile, addGreeting: Boolean = false) {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
        val switchingCharacter = activeBotId != bot.id
        if (currentPage != "conversation") {
            conversationReturnPage = if (currentPage == "chats") "chats" else "discover"
        }
        saveMessages()
        activeBotId = bot.id
        prefs().edit().putString(KEY_ACTIVE_BOT, activeBotId).apply()
        loadMessagesForBot(activeBotId)
        if (addGreeting && bot.greeting.isNotBlank() && messages.isEmpty()) {
            messages.add(ChatMessage("assistant", bot.greeting))
            saveMessages()
        }
        currentPage = "conversation"
        renderPage()

        // The inference engine accepts a system prompt only immediately after loadModel().
        // Reload on character switches so the selected bot's prompt is installed at the valid point.
        if (switchingCharacter && engineReady && activeModelName != null) {
            val file = modelFile(activeModelName!!)
            if (file.exists()) loadModelFile(file, false)
        }
    }

    private fun createBot() {
        currentPage = "create_bot"
        renderPage()
    }

    private fun renderCreateBotPage() {
        val editingBot = editingBotId?.let { id -> bots.firstOrNull { it.id == id } }
        header.text = if (editingBot == null) "Create Bot" else "Edit Bot"
        addSectionTitle(
            if (editingBot == null) "Ciptakan karakter" else "Edit " + editingBot.name,
            if (editingBot == null) "Rancang karakter sesuai imajinasimu." else "Ubah detail karakter tanpa menghapus riwayat percakapannya."
        )
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
        val name = field("Nama karakter *").apply { setText(editingBot?.name.orEmpty()) }
        val tagline = field("Tagline singkat").apply { setText(editingBot?.tagline.orEmpty()) }
        val description = field("Deskripsi karakter", 3).apply { setText(editingBot?.description.orEmpty()) }
        val greeting = field("Greeting / sapaan awal", 3).apply { setText(editingBot?.greeting.orEmpty()) }
        val prompt = field("Kepribadian, gaya bicara, dan latar belakang", 5).apply {
            setText(editingBot?.systemPrompt.orEmpty())
        }
        val example = field("Contoh dialog (opsional)", 3).apply { setText(editingBot?.exampleDialog.orEmpty()) }
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
                if (userPersona.isNotBlank()) append("\n\nPersona pengguna: ").append(userPersona)
            }.trim()
            val updatedBot = if (editingBot != null) {
                val replacement = editingBot.copy(
                    name = botName,
                    systemPrompt = fullPrompt,
                    tagline = tagline.text.toString().trim(),
                    description = botDescription,
                    greeting = botGreeting,
                    exampleDialog = exampleText
                )
                val index = bots.indexOfFirst { it.id == editingBot.id }
                if (index >= 0) bots[index] = replacement
                editingBotId = null
                replacement
            } else {
                BotProfile(
                    UUID.randomUUID().toString(), botName, fullPrompt, null,
                    tagline.text.toString().trim(), botDescription, botGreeting, exampleText
                ).also { bots.add(it) }
            }
            saveBots()
            if (editingBot != null) {
                if (activeBotId == updatedBot.id) {
                    openBotConversation(updatedBot)
                    activeModelName?.let { modelName ->
                        val model = modelFile(modelName)
                        if (model.exists()) loadModelFile(model, false)
                    }
                } else {
                    currentPage = "discover"
                    renderPage()
                }
                toast("Perubahan " + updatedBot.name + " tersimpan.")
            } else {
                openBotConversation(updatedBot, addGreeting = true)
                toast("Karakter " + botName + " tersimpan. Ketuk avatar di header untuk mengganti fotonya.")
            }
        }
        addAction(chatColumn, "Batal") {
            editingBotId = null
            currentPage = "discover"
            renderPage()
        }
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
        startAssistantGeneration(text, assistant)
    }

    private fun startAssistantGeneration(prompt: String, assistant: ChatMessage) {
        val predictLength = minOf(automaticPredictLength(prompt), activeSamplingTune().maxTokens)
        currentAssistantView = (chatColumn.getChildAt(chatColumn.childCount - 1) as? LinearLayout)?.getChildAt(0) as? TextView
        input.isEnabled = false
        sendButton.isEnabled = false
        status.text = "Bot sedang mengetik..."
        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // The selected bot's system prompt is installed immediately after loadModel().
                // Do not call setSystemPrompt here: this engine rejects late prompt changes.
                engine.sendUserPrompt(prompt, predictLength = predictLength).collect { token ->
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

    private fun automaticPredictLength(prompt: String): Int {
        val text = prompt.lowercase()
        val shortRequest = listOf("singkat", "ringkas", "pendek saja", "jawab pendek", "satu kalimat", "ya atau tidak", "jawaban singkat")
            .any { text.contains(it) }
        if (shortRequest) return 96

        val longRequest = listOf(
            "jelaskan detail", "secara rinci", "lebih lengkap", "panjang", "ceritakan", "lanjutkan cerita",
            "lanjutkan", "narasikan", "adegan", "roleplay", "in detail", "long answer", "continue the story",
            "describe", "story", "scene"
        ).any { text.contains(it) }
        val characterRoleplay = activeBot().id != "default" &&
            (prompt.length > 45 || listOf("aku ", "saya ", "dia ", "lalu ", "kemudian ").any { text.contains(it) })
        if (longRequest || prompt.length > 180 || characterRoleplay) return 320
        if (prompt.trim().split(Regex("\\s+")).size <= 7) return 128
        return 192
    }

    private fun showChatActions() {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(activeBot().name)
            .setItems(arrayOf("Clean Chat", "Delete Chat", "Regenerate jawaban terakhir")) { _, which ->
                when (which) {
                    0 -> cleanCurrentChat()
                    1 -> deleteCurrentChat()
                    2 -> regenerateLastResponse()
                }
            }
            .show()
    }

    private fun cleanCurrentChat() {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses yang sedang berjalan selesai.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clean Chat?")
            .setMessage("Percakapan ${activeBot().name} akan dikosongkan. Riwayat bot lain tetap aman.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Bersihkan") { _, _ ->
                messages.clear()
                saveMessages()
                renderMessages()
                toast("Chat ${activeBot().name} dibersihkan.")
                activeModelName?.let { name ->
                    val file = modelFile(name)
                    if (file.exists()) loadModelFile(file, false)
                }
            }
            .show()
    }

    private fun deleteCurrentChat() {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses yang sedang berjalan selesai.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus chat?")
            .setMessage("Riwayat ${activeBot().name} akan dihapus dari tab Chats. Karakternya tetap ada di Discover.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                val botId = activeBotId
                messages.clear()
                prefs().edit()
                    .remove(KEY_MESSAGES_PREFIX + botId)
                    .putString(KEY_MESSAGES, "[]")
                    .apply()
                currentPage = conversationReturnPage
                renderPage()
                toast("Riwayat chat dihapus.")
                activeModelName?.let { name ->
                    val file = modelFile(name)
                    if (file.exists()) loadModelFile(file, false)
                }
            }
            .show()
    }

    private fun deleteBotChat(bot: BotProfile) {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses yang sedang berjalan selesai.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus chat ${bot.name}?")
            .setMessage("Riwayat percakapan ini akan dihapus. Karakter tetap tersedia di Discover.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                prefs().edit().remove(KEY_MESSAGES_PREFIX + bot.id).apply()
                if (bot.id == activeBotId) {
                    messages.clear()
                    prefs().edit().putString(KEY_MESSAGES, "[]").apply()
                    activeModelName?.let { name ->
                        val file = modelFile(name)
                        if (file.exists()) loadModelFile(file, false)
                    }
                }
                toast("Riwayat ${bot.name} dihapus.")
                renderPage()
            }
            .show()
    }

    private fun regenerateLastResponse() {
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses yang sedang berjalan selesai.")
            return
        }
        if (messages.isEmpty() || messages.last().role != "assistant") {
            toast("Belum ada jawaban terakhir yang bisa dibuat ulang.")
            return
        }
        val assistantIndex = messages.lastIndex
        val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { messages[it].role == "user" }
        if (userIndex == null) {
            toast("Pesan pengguna sebelumnya tidak ditemukan.")
            return
        }
        val model = activeModelName?.let { modelFile(it) }
        if (model == null || !model.exists()) {
            toast("Pilih ulang model sebelum regenerasi.")
            return
        }
        val prompt = messages[userIndex].text
        messages.removeAt(assistantIndex)
        val replacement = ChatMessage("assistant", "")
        messages.add(replacement)
        saveMessages()
        renderMessages()
        val priorHistory = messages.take(userIndex).toList()
        loadModelFile(
            model,
            false,
            priorHistory,
            onReady = { startAssistantGeneration(prompt, replacement) },
            onFailure = { reason ->
                replacement.text = "Gagal menyiapkan regenerasi: $reason"
                saveMessages()
                renderMessages()
                input.isEnabled = true
                sendButton.isEnabled = true
                toast("Regenerasi gagal disiapkan.")
            }
        )
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
                        openBotConversation(bot)
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
                val deleteButton = Button(this).apply {
                    text = "Hapus"
                    textSize = 10f
                    minWidth = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { deleteBotChat(bot) }
                }
                card.addView(deleteButton)
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
        activeTuneId = prefs().getString(KEY_ACTIVE_TUNE, "default") ?: "default"
        manualTune = SamplingTune(
            "manual", "Manual",
            prefs().getFloat(KEY_MANUAL_TEMPERATURE, 0.7f),
            prefs().getFloat(KEY_MANUAL_TOP_P, 0.9f),
            prefs().getInt(KEY_MANUAL_TOP_K, 40),
            prefs().getFloat(KEY_MANUAL_REPEAT_PENALTY, 1.1f),
            prefs().getInt(KEY_MANUAL_MAX_TOKENS, 192)
        )
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
        ensureSampleBots()
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

    private fun ensureSampleBots() {
        val samples = listOf(
            BotProfile(
                id = "sample_aira",
                name = "Aira",
                systemPrompt = "Kamu Aira, penjaga toko buku yang hangat, peka, sedikit pemalu, dan punya humor lembut. Bangun romansa slow-burn yang saling menghormati. Jangan memaksa hubungan; biarkan kedekatan tumbuh dari percakapan dan tindakan kecil.",
                tagline = "Romansa slow-burn di toko buku kecil",
                description = "Romansa modern tentang dua orang yang sering bertemu di toko buku saat hujan. Hangat, manis, perlahan, dan penuh momen kecil.",
                greeting = "*Aira menutup novel yang sedang dibacanya lalu tersenyum kecil.* Kamu datang lagi. Lagi cari buku baru, atau sebenarnya mau mengobrol denganku?",
                exampleDialog = "Pengguna: Hari ini melelahkan.\nAira: *Ia menggeser secangkir teh hangat ke arahmu.* Kalau begitu, duduk dulu. Kamu nggak harus menceritakan semuanya sekarang."
            ),
            BotProfile(
                id = "sample_mika",
                name = "Mika",
                systemPrompt = "Kamu Mika, barista ceria di kafe 24 jam yang suka bercanda, jago mendengarkan, dan spontan mengajak pengguna masuk ke roleplay slice-of-life. Beri respons natural, dialog yang hidup, dan detail suasana secukupnya.",
                tagline = "Roleplay kafe tengah malam",
                description = "Roleplay slice-of-life di kafe kota yang selalu buka. Obrolan santai, pertemanan, rahasia pelanggan, dan kejadian spontan.",
                greeting = "*Lonceng pintu kafe berbunyi. Mika melambaikan tangan dari balik mesin kopi.* Selamat datang! Duduk di tempat biasa? Aku baru bikin menu eksperimen—dan aku butuh kelinci percobaan.",
                exampleDialog = "Pengguna: Menu eksperimen?\nMika: Namanya Kopi Galau Level Tiga. Rasanya enak, tapi efek sampingnya bikin kamu jujur soal perasaan. *Ia menahan tawa."
            ),
            BotProfile(
                id = "sample_elowen",
                name = "Elowen",
                systemPrompt = "Kamu Elowen, elf penjaga hutan kuno dalam dunia fantasi. Bijaksana, tenang, penuh rasa ingin tahu, dan sedikit sarkastik. Gunakan narasi atmosferis serta dialog karakter, tetapi tetap beri ruang bagi pengguna untuk menentukan tindakannya sendiri.",
                tagline = "Fantasi elf dan hutan terlarang",
                description = "Fantasi epik dengan elf, sihir kuno, reruntuhan, roh hutan, dan perjalanan misterius.",
                greeting = "*Cahaya hijau berpendar di antara pepohonan. Seorang elf menurunkan busurnya, tetapi belum melepaskan kewaspadaan.* Jarang manusia menemukan jalan ini. Katakan, pengelana—apa yang membawamu ke Hutan Elarion?",
                exampleDialog = "Pengguna: Aku mencari artefak kuno.\nElowen: *Telinganya bergerak saat angin berbisik.* Semua orang mencari artefak. Yang jarang mereka tanyakan adalah apa yang sedang menunggu untuk ditemukan oleh artefak itu."
            ),
            BotProfile(
                id = "sample_kael",
                name = "Kael",
                systemPrompt = "Kamu Kael, pangeran naga yang dapat berubah menjadi manusia. Bangga, protektif, cerdas, dan menyembunyikan kesepian di balik sikap dingin. Bangun roleplay fantasi dengan konflik, pilihan bermakna, dan narasi yang sinematik tanpa mengambil alih tindakan pengguna.",
                tagline = "Pangeran naga dengan rahasia kuno",
                description = "Fantasi kerajaan naga, perjanjian sihir, intrik istana, dan petualangan antara manusia dengan naga.",
                greeting = "*Asap tipis keluar dari sela-sela bibirnya. Kael menatapmu dari singgasana obsidian.* Kau berani memasuki wilayahku tanpa undangan. Aku seharusnya mengusirmu... jadi, beri aku satu alasan untuk tidak melakukannya.",
                exampleDialog = "Pengguna: Aku tidak takut padamu.\nKael: *Senyumnya tipis, kilatan keemasan melintas di matanya.* Bagus. Rasa takut membuat percakapan membosankan. Keberanian, sebaliknya, bisa menjadi sangat merepotkan."
            )
        )
        var changed = false
        val deletedSamples = prefs().getStringSet(KEY_DELETED_SAMPLE_BOTS, emptySet()) ?: emptySet()
        samples.forEach { sample ->
            if (sample.id !in deletedSamples && bots.none { it.id == sample.id }) {
                bots.add(sample)
                changed = true
            }
        }
        if (changed) saveBots()
    }

    private fun showDeleteBotConfirmation(bot: BotProfile) {
        if (bot.id == "default") {
            toast("Bot bawaan utama tidak bisa dihapus.")
            return
        }
        if (generationJob?.isActive == true || modelLoadInProgress || modelImportInProgress) {
            toast("Tunggu proses model atau jawaban selesai dulu.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus bot ${bot.name}?")
            .setMessage("Karakter, sapaan, dan riwayat chat bot ini akan dihapus dari aplikasi. Tindakan ini tidak bisa dibatalkan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus bot") { _, _ ->
                val wasActive = bot.id == activeBotId
                bots.removeAll { it.id == bot.id }
                if (bot.id.startsWith("sample_")) {
                    val deleted = (prefs().getStringSet(KEY_DELETED_SAMPLE_BOTS, emptySet()) ?: emptySet()).toMutableSet()
                    deleted.add(bot.id)
                    prefs().edit().putStringSet(KEY_DELETED_SAMPLE_BOTS, deleted).apply()
                }
                prefs().edit().remove(KEY_MESSAGES_PREFIX + bot.id).apply()
                saveBots()
                if (wasActive) {
                    val fallback = bots.firstOrNull { it.id == "default" } ?: bots.first()
                    activeBotId = fallback.id
                    messages.clear()
                    prefs().edit()
                        .putString(KEY_ACTIVE_BOT, fallback.id)
                        .putString(KEY_MESSAGES, "[]")
                        .apply()
                    if (currentPage == "conversation") currentPage = "discover"
                    activeModelName?.let { name ->
                        val file = modelFile(name)
                        if (file.exists()) loadModelFile(file, false)
                    }
                }
                renderPage()
                toast("Bot ${bot.name} dihapus.")
            }
            .show()
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

    private fun buildCharacterSystemPrompt(
        bot: BotProfile,
        history: List<ChatMessage> = messages
    ): String = buildString {
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
        appendLine("- Atur panjang jawaban otomatis: singkat untuk pertanyaan sederhana, sedang untuk obrolan biasa, dan lebih panjang saat diminta detail atau saat adegan roleplay memang membutuhkan narasi.")
        appendLine("- Jangan memanjangkan jawaban tanpa alasan; utamakan respons alami dan sesuai konteks.")
        val recentHistory = history.filter { it.text.isNotBlank() }.takeLast(6)
        if (recentHistory.isNotEmpty()) {
            appendLine()
            appendLine("KONTEKS PERCAKAPAN SEBELUMNYA — gunakan untuk kesinambungan, jangan mengulangnya:")
            recentHistory.forEach { message ->
                val speaker = if (message.role == "user") "Pengguna" else bot.name
                appendLine("$speaker: ${message.text.take(180)}")
            }
        }
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

    private data class SamplingTune(
        val id: String,
        val label: String,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val repeatPenalty: Float,
        val maxTokens: Int
    )
    private data class ChatMessage(val role: String, var text: String)
    private data class BlackjackChatMessage(val fromUser: Boolean, val text: String)
    private data class BlackjackCard(val rank: Int, val suit: String) {
        val display: String get() = (when (rank) { 1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> rank.toString() }) + suit
        fun rankValue(): Int = when (rank) { 1 -> 11; in 11..13 -> 10; else -> rank }
    }
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
        private const val KEY_ACTIVE_TUNE = "active_model_tune"
        private const val KEY_MANUAL_TEMPERATURE = "manual_tune_temperature"
        private const val KEY_MANUAL_TOP_P = "manual_tune_top_p"
        private const val KEY_MANUAL_TOP_K = "manual_tune_top_k"
        private const val KEY_MANUAL_REPEAT_PENALTY = "manual_tune_repeat_penalty"
        private const val KEY_MANUAL_MAX_TOKENS = "manual_tune_max_tokens"
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
        private const val KEY_DELETED_SAMPLE_BOTS = "deleted_sample_bots"
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
