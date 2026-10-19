package com.sandev.shamancompanion

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val bg = Color.rgb(17, 19, 34)
    private val panel = Color.rgb(31, 35, 57)
    private val accent = Color.rgb(185, 142, 255)
    private val mint = Color.rgb(111, 232, 190)
    private val muted = Color.rgb(183, 190, 215)
    private lateinit var status: TextView
    private lateinit var sessionButton: Button
    private lateinit var cooldownText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var sessionActive = false
    private val cooldowns = linkedMapOf("Heal" to 0L, "Buff" to 0L, "Support" to 0L)
    private val durations = mapOf("Heal" to 5, "Buff" to 30, "Support" to 12)

    private val ticker = object : Runnable {
        override fun run() {
            refreshCooldowns()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        handler.post(ticker)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("SANDEV", 13, accent, true))
        root.addView(label("SHAMAN COMPANION", 26, Color.WHITE, true))
        root.addView(label("Partner support untuk petualangan RAN Mobile", 14, muted, false))
        root.addView(space(18))

        val statusCard = card()
        statusCard.addView(label("STATUS KONEKSI", 12, accent, true))
        status = label("Belum terhubung ke RAN Mobile", 17, Color.WHITE, true)
        statusCard.addView(status)
        statusCard.addView(label("Versi awal: companion manual. Aplikasi ini belum membaca layar atau mengendalikan game.", 13, muted, false))
        root.addView(statusCard)
        root.addView(space(14))

        val sessionCard = card()
        sessionCard.addView(label("SESI BERMAIN", 12, accent, true))
        sessionCard.addView(label("Aktifkan sesi saat kamu mulai bermain. Timer skill berjalan di aplikasi ini.", 14, muted, false))
        sessionButton = Button(this).apply {
            text = "MULAI SESI"
            setTextColor(bg)
            setBackgroundColor(mint)
            setOnClickListener {
                sessionActive = !sessionActive
                text = if (sessionActive) "AKHIRI SESI" else "MULAI SESI"
                status.text = if (sessionActive) "Sesi aktif · menunggu input manual" else "Sesi dijeda"
                status.setTextColor(if (sessionActive) mint else Color.WHITE)
            }
        }
        sessionCard.addView(sessionButton, matchWrap())
        root.addView(sessionCard)
        root.addView(space(14))

        val skillsCard = card()
        skillsCard.addView(label("PANEL SKILL SHAMAN", 12, accent, true))
        skillsCard.addView(label("Tekan setelah kamu menggunakan skill di dalam game untuk memulai cooldown pengingat.", 13, muted, false))
        cooldownText = label("", 15, Color.WHITE, true)
        cooldownText.setPadding(0, dp(10), 0, dp(8))
        skillsCard.addView(cooldownText)
        listOf("Heal", "Buff", "Support").forEach { skill ->
            val button = Button(this).apply {
                text = "$skill · mulai cooldown"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(72, 65, 108))
                setOnClickListener {
                    cooldowns[skill] = System.currentTimeMillis() + durations.getValue(skill) * 1000L
                    refreshCooldowns()
                }
            }
            skillsCard.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        }
        root.addView(skillsCard)
        root.addView(space(14))

        val roadmap = card()
        roadmap.addView(label("ROADMAP", 12, accent, true))
        roadmap.addView(label("✓ Companion dan timer manual\n• Validasi kompatibilitas RAN Mobile\n• Evaluasi akses informasi layar dengan izin pengguna\n• Uji kontrol terbatas hanya jika diizinkan game", 14, Color.WHITE, false))
        root.addView(roadmap)
        root.addView(space(10))
        root.addView(label("Jangan masukkan password atau OTP akun game ke aplikasi companion.", 12, muted, false))
    }

    private fun refreshCooldowns() {
        if (!::cooldownText.isInitialized) return
        val now = System.currentTimeMillis()
        cooldownText.text = cooldowns.entries.joinToString("\n") { (name, end) ->
            val secondsLeft = ((end - now).coerceAtLeast(0L) + 999L) / 1000L
            if (secondsLeft > 0) "$name: ${secondsLeft}s" else "$name: siap"
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(panel)
        elevation = dp(2).toFloat()
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
