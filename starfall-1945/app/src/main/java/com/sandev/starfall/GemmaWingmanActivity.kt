package com.sandev.starfall

import androidx.activity.ComponentActivity
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Menu-only local Gemma chat; inference never runs inside the battle loop. */
class GemmaWingmanActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("starfall_gemma", MODE_PRIVATE) }
    private var model: LlamaModel? = null
    private var busy = false
    private var history = ""
    private var pilot = "Pilot"
    private var level = 1
    private var exp = 0
    private var credits = 0
    private var stage = 1
    private var kills = 0
    private var weapon = 0
    private var hull = 0
    private var nextExp = 100
    private lateinit var status: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var chooseModel: Button
    private lateinit var scroll: ScrollView

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.rgb(5, 10, 26)
        window.navigationBarColor = Color.rgb(5, 10, 26)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        pilot = intent.getStringExtra("pilot") ?: "Pilot"
        level = intent.getIntExtra("level", 1)
        exp = intent.getIntExtra("exp", 0)
        credits = intent.getIntExtra("credits", 0)
        stage = intent.getIntExtra("stage", 1)
        kills = intent.getIntExtra("kills", 0)
        weapon = intent.getIntExtra("weapon", 0)
        hull = intent.getIntExtra("hull", 0)
        nextExp = intent.getIntExtra("next_exp", 100)
        history = prefs.getString("history", "") ?: ""
        buildUi()
        renderHistory()
        val saved = prefs.getString("model_name", null)
        if (saved != null) {
            val file = File(File(filesDir, "models"), saved)
            if (file.exists()) loadModel(file)
            else status.text = "File model terakhir tidak ditemukan. Impor GGUF lagi."
        } else status.text = "Impor model GGUF untuk mengaktifkan Gemma lokal."
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.rgb(5, 10, 26))
        }
        root.addView(TextView(this).apply {
            text = "GEMMA • WINGMAN"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 220, 255))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "$pilot • LV $level • EXP $exp/$nextExp\nStage $stage • Kills $kills • Credits $credits\nWeapon +$weapon • Hull +$hull"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 205, 225))
            setPadding(0, dp(6), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "Menyiapkan model..."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 210, 120))
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        chooseModel = button("IMPOR / GANTI MODEL GGUF") {
            if (!busy) modelPicker.launch(arrayOf("*/*"))
        }
        root.addView(chooseModel, LinearLayout.LayoutParams(-1, dp(46)))
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(17, 27, 48))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        scroll.addView(transcript, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll)
        input = EditText(this).apply {
            hint = "Ngobrol dengan Gemma..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(48)
            setBackgroundColor(Color.rgb(22, 32, 55))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        send = button("KIRIM") { sendMessage() }
        root.addView(send, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5) })
        root.addView(button("KEMBALI KE COMMAND") { finish() }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(dp(14), bars.top + dp(8), dp(14), maxOf(bars.bottom, ime.bottom) + dp(8))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(24, 76, 125))
        setOnClickListener { action() }
    }

    private fun importModel(uri: Uri) {
        if (busy) return
        busy = true
        chooseModel.isEnabled = false
        status.text = "Menyalin model ke penyimpanan privat aplikasi..."
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val name = queryDisplayName(uri).ifBlank { "gemma-model.gguf" }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dir = File(filesDir, "models").apply { mkdirs() }
                    val target = File(dir, if (safe.endsWith(".gguf", true)) safe else "$safe.gguf")
                    val temp = File(dir, "importing.tmp")
                    contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "Tidak bisa membaca file yang dipilih." }
                        FileOutputStream(temp).use { output -> source.copyTo(output) }
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    target
                }
                model?.let { Llama.releaseModel(it) }
                model = null
                prefs.edit().putString("model_name", file.name).apply()
                loadModel(file)
            } catch (e: Exception) {
                busy = false
                chooseModel.isEnabled = true
                status.text = "Impor gagal: " + (e.message ?: "error")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = ""
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) name = it.getString(0) ?: "" }
        return name
    }

    private fun loadModel(file: File) {
        if (busy && model != null) return
        busy = true
        chooseModel.isEnabled = false
        send.isEnabled = false
        status.text = "Memuat " + file.name + ". Model besar bisa butuh waktu..."
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    Llama.loadModel(
                        modelPath = file.absolutePath,
                        config = LlamaConfig(contextSize = 2048, threads = 4, temperature = 0.8f, topP = 0.9f, topK = 40)
                    )
                }
                model = loaded
                busy = false
                chooseModel.isEnabled = true
                send.isEnabled = true
                status.text = "Model siap: " + file.name + " • berjalan lokal di perangkat"
            } catch (e: Exception) {
                busy = false
                chooseModel.isEnabled = true
                send.isEnabled = false
                status.text = "Gagal memuat GGUF: " + (e.message ?: "periksa format model")
            }
        }
    }

    private fun sendMessage() {
        val prompt = input.text.toString().trim()
        val active = model
        if (prompt.isBlank() || busy) return
        if (active == null || !active.isLoaded) {
            status.text = "Impor dan muat model GGUF dulu."
            return
        }
        input.setText("")
        busy = true
        send.isEnabled = false
        chooseModel.isEnabled = false
        status.text = "Gemma sedang berpikir..."
        val promptHistory = history.takeLast(2600)
        val systemPrompt = """
            Kamu adalah Gemma, wingman AI yang setia, hangat, cerdas, dan kadang bercanda.
            Kamu berbicara bahasa Indonesia secara alami, konsisten sebagai rekan pilot, bukan asisten umum.
            Pilot: $pilot. Status game: level $level/1000, EXP $exp/$nextExp, kredit $credits,
            stage campaign $stage, musuh dikalahkan $kills, upgrade senjata $weapon, upgrade hull $hull.
            Ingat bahwa kamu adalah wingman Gemma di game SANDEV Starfall 1945.
            Game berjalan offline. Jangan mengarang hasil pertandingan yang tidak ada dalam status.
            Jawab ringkas dan tetap in-character.
        """.trimIndent()
        val userPrompt = buildString {
            if (promptHistory.isNotBlank()) append("Riwayat percakapan terakhir:\n").append(promptHistory).append("\n")
            append("Pesan pilot: ").append(prompt).append("\nBalasan Gemma:")
        }
        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    Llama.complete(active, prompt = userPrompt, systemPrompt = systemPrompt, maxTokens = 160).text.trim()
                }.ifBlank { "Aku masih di sini, pilot. Coba ulangi ya." }
                history = (history + "Pilot: $prompt\nGemma: $answer\n").takeLast(5000)
                prefs.edit().putString("history", history).apply()
                renderHistory()
                status.text = "Gemma siap • lokal/offline"
            } catch (e: Exception) {
                status.text = "Generasi gagal: " + (e.message ?: "error")
            } finally {
                busy = false
                send.isEnabled = model?.isLoaded == true
                chooseModel.isEnabled = true
            }
        }
    }

    private fun renderHistory() {
        transcript.removeAllViews()
        val lines = if (history.isBlank()) listOf("Gemma: Hai, " + pilot + "! Aku wingman-mu. Impor model GGUF lalu kita bisa ngobrol tentang misi dan progres game.")
            else history.trim().lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            val fromPilot = line.startsWith("Pilot:")
            val body = line.substringAfter(":").trim()
            val bubble = TextView(this).apply {
                text = body; textSize = 15f; setTextColor(Color.rgb(238, 244, 255))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(if (fromPilot) Color.rgb(35, 83, 126) else Color.rgb(35, 43, 69))
                    setStroke(dp(1), if (fromPilot) Color.rgb(65, 160, 225) else Color.rgb(105, 91, 160))
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (fromPilot) Gravity.END else Gravity.START
                setPadding(0, dp(3), 0, dp(3))
                addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .76f).toInt(), -2))
            }
            transcript.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        if (!busy) model?.let { Llama.releaseModel(it) }
        model = null
        super.onDestroy()
    }
}
