package com.sandev.starfall

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import androidx.lifecycle.lifecycleScope
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var pilot = ""
    private var level = 1
    private var exp = 0
    private var credits = 250
    private var stage = 1
    private var kills = 0
    private var weapon = 0
    private var hull = 0
    private var page = "home"
    private var battleModel: LlamaModel? = null
    private var battleInferenceBusy = false
    private var battleModelChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 10, 26)
        window.navigationBarColor = Color.rgb(5, 10, 26)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("starfall_profile", Context.MODE_PRIVATE)
        load()
        showHome()
    }
    private fun load() {
        pilot = prefs.getString("pilot", "") ?: ""
        level = prefs.getInt("level", 1).coerceIn(1, 1000)
        exp = prefs.getInt("exp", 0); credits = prefs.getInt("credits", 250)
        stage = prefs.getInt("stage", 1); kills = prefs.getInt("kills", 0)
        weapon = prefs.getInt("weapon", 0); hull = prefs.getInt("hull", 0)
    }
    private fun save() {
        prefs.edit().putString("pilot", pilot).putInt("level", level).putInt("exp", exp)
            .putInt("credits", credits).putInt("stage", stage).putInt("kills", kills)
            .putInt("weapon", weapon).putInt("hull", hull).apply()
    }
    private fun needed() = 100 + level * 18
    private fun reward(xp: Int, money: Int) {
        exp += xp; credits += money
        while (level < 1000 && exp >= needed()) { exp -= needed(); level++ }
        save()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun layout(title: String, subtitle: String = ""): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(16)); setBackground(android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 20, 46), Color.rgb(5, 9, 25))).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(32, 91, 135)) })
        }
        root.addView(TextView(this).apply {
            text = title; textSize = 25f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 220, 255)); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, -2))
        if (subtitle.isNotBlank()) root.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 205, 225)); setPadding(0, dp(8), 0, dp(14))
        }, LinearLayout.LayoutParams(-1, -2))
        return root
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        backgroundTintList = null
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(24, 91, 143), Color.rgb(25, 53, 105))).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(63, 155, 207)) }
        setOnClickListener { action() }
    }
    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(button(label, action), LinearLayout.LayoutParams(-1, dp(47)).apply {
            topMargin = dp(4); bottomMargin = dp(3)
        })
    }
    private fun text(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; gravity = Gravity.CENTER
        setTextColor(Color.rgb(225, 235, 250)); setPadding(dp(6), dp(8), dp(6), dp(8))
    }
    private fun showHome() {
        page = "home"
        val root = layout("SANDEV", "STARFALL 1945 • OFFLINE SPACE RPG")
        root.addView(LogoView(this), LinearLayout.LayoutParams(-1, dp(218)))
        root.addView(text("CLASSIC SPACE RPG  •  OFFLINE", 12f))
        if (pilot.isBlank()) {
            val input = EditText(this).apply {
                hint = "Create Player — pilot name"; setSingleLine(true)
                setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
                setPadding(dp(12), 0, dp(12), 0); setBackgroundColor(Color.rgb(22, 32, 55))
            }
            root.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addButton(root, "CREATE PLAYER") {
                val name = input.text.toString().trim()
                if (name.isBlank()) input.error = "Enter a pilot name"
                else { pilot = name.take(24); save(); showHome() }
            }
        } else {
            root.addView(text("Pilot: $pilot • Level $level", 15f))
            addButton(root, "PLAY") { showMenu() }
            addButton(root, "CHANGE PILOT NAME") { pilot = ""; showHome() }
        }
        setContentView(root)
    }
    private fun showMenu() {
        page = "menu"
        val root = layout("STARFALL COMMAND", "$pilot  •  LV $level  •  $credits CR")
        root.setPadding(dp(14), dp(12), dp(14), dp(10))
        root.addView(text("FRONTIER SECTOR  /  STAGE $stage", 12f))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(items); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        items.addView(text("MISSION CONTROL", 12f))
        addButton(items, "🚀  CAMPAIGN  •  STAGE $stage") { battle() }
        items.addView(text("SHIP & UPGRADES", 12f))
        addButton(items, "🛸  HANGAR / SHIP STATUS") { hangar() }
        addButton(items, "⚙  WORKSHOP / SHOP") { shop() }
        addButton(items, "✦  SKILL LAB") { skillLab() }
        items.addView(text("PILOT SYSTEMS", 12f))
        addButton(items, "💜  GEMMA AI  •  CHAT & PROFILE") { gemma() }
        addButton(items, "▤  MISSIONS / SECTOR MAP") { sectorMap() }
        addButton(items, "◷  BATTLE RECORDS") { records() }
        addButton(items, "⚙  SETTINGS") { settingsPage() }
        addButton(root, "▶  DEPLOY TO BATTLE") { battle() }
        setContentView(root)
    }

    private fun hangar() {
        page = "hangar"
        val selected = prefs.getString("ship_class", "NOVA") ?: "NOVA"
        val root = layout("STARFIGHTER HANGAR", "Pilih kapal • pilihan tersimpan lokal")
        root.addView(text("NOVA-01 • BALANCED FIGHTER\nHull dan serangan seimbang.", 14f))
        addButton(root, if (selected == "NOVA") "✓ NOVA-01 EQUIPPED" else "EQUIP NOVA-01") { prefs.edit().putString("ship_class", "NOVA").apply(); hangar() }
        root.addView(text("GUARDIAN • HEAVY HULL\nHP lebih tinggi untuk bertahan.", 14f))
        addButton(root, if (selected == "GUARDIAN") "✓ GUARDIAN EQUIPPED" else "EQUIP GUARDIAN") { prefs.edit().putString("ship_class", "GUARDIAN").apply(); hangar() }
        root.addView(text("STRIKER • FAST ATTACK\nTembakan lebih cepat dan damage tinggi.", 14f))
        addButton(root, if (selected == "STRIKER") "✓ STRIKER EQUIPPED" else "EQUIP STRIKER") { prefs.edit().putString("ship_class", "STRIKER").apply(); hangar() }
        root.addView(text("Loadout: " + selected + " • Weapon +" + weapon + " • Hull +" + hull, 13f))
        addButton(root, "WORKSHOP / UPGRADES") { shop() }
        addButton(root, "BACK TO COMMAND") { showMenu() }
        setContentView(root)
    }
    private fun skillLab() {
        page = "skills"
        val root = layout("SKILL LAB", "PILOT LV $level • STAGE $stage")
        root.addView(text("AUTO AREA BOMB\n\nMeledak otomatis setiap 5 detik. Tidak perlu tombol atau input.\n\nArea ledakan bertambah setiap 20 level: LV 1–19 area awal, LV 20–39 area lebih luas, dan seterusnya. Damage ikut meningkat perlahan seiring level pilot.", 15f))
        root.addView(text("GEMMA • AUTO COVER FIRE\n\nGemma tetap menembak otomatis sambil bergerak menghindari ancaman. Skill AI yang tersedia dipakai otomatis saat keputusan valid diterima.", 14f))
        addButton(root, "BACK TO COMMAND") { showMenu() }
        setContentView(root)
    }
    private fun sectorMap() {
        page = "map"
        val root = layout("SECTOR MAP", "FRONTIER SECTOR • CAMPAIGN")
        root.addView(text("SECTOR " + stage + "\n30 hostiles, lalu Dreadnought boss.\nClear stage membuka sektor berikutnya.\n\nPilot level " + level + " • Total kills " + kills, 15f))
        addButton(root, "DEPLOY TO SECTOR " + stage) { battle() }
        addButton(root, "BACK TO COMMAND") { showMenu() }
        setContentView(root)
    }
    private fun settingsPage() {
        page = "settings"
        val root = layout("STARFALL SETTINGS", "LOCAL PROFILE • ON-DEVICE")
        val hints = prefs.getBoolean("battle_hints", true)
        val autoLoad = prefs.getBoolean("battle_gemma_autoload", true)
        addButton(root, "Battle hints: " + if (hints) "ON" else "OFF") { prefs.edit().putBoolean("battle_hints", !hints).apply(); settingsPage() }
        addButton(root, "Auto-load Gemma GGUF: " + if (autoLoad) "ON" else "OFF") { prefs.edit().putBoolean("battle_gemma_autoload", !autoLoad).apply(); settingsPage() }
        addButton(root, "BACK TO COMMAND") { showMenu() }
        setContentView(root)
    }
    private fun requestBattleGemma(prompt: String, callback: (String) -> Unit) {
        if (battleInferenceBusy) { callback(""); return }
        battleInferenceBusy = true
        lifecycleScope.launch {
            try {
                var active = battleModel
                if (active == null && !battleModelChecked) {
                    val name = getSharedPreferences("starfall_gemma", MODE_PRIVATE).getString("model_name", null)
                    val file = name?.let { File(File(filesDir, "models"), it) }
                    if (file != null && file.exists() && prefs.getBoolean("battle_gemma_autoload", true)) {
                        active = withContext(Dispatchers.IO) {
                            Llama.loadModel(file.absolutePath, LlamaConfig(contextSize = 2048, threads = 4, temperature = 0.85f, topP = 0.9f, topK = 40))
                        }
                        battleModel = active
                    }
                    battleModelChecked = true
                }
                if (active == null || !active.isLoaded) callback("")
                else {
                    val system = "Kamu Gemma, wingman AI lokal di SANDEV Starfall 1945. Pilih aksi dari status battle dan hanya gunakan skill yang dinyatakan terbuka di status. Balas dua baris: ACTION: ATTACK atau DODGE atau COVER atau BOSS atau TARGET_LOCK atau COVER_FIRE atau EVADE atau COORDINATED; baris kedua SAY: komentar pendek bahasa Indonesia. ATTACK menyerang, DODGE menghindari bahaya, COVER mendekati pilot, BOSS fokus boss, TARGET_LOCK mengunci target, COVER_FIRE menembak salvo, EVADE menghindar cepat, COORDINATED serangan gabungan. Jangan tulis analisis atau format lain."
                    val readyModel = active
                    callback(withContext(Dispatchers.IO) { Llama.complete(readyModel, prompt = prompt, systemPrompt = system, maxTokens = 96).text.trim() })
                }
            } catch (_: Exception) { callback("") }
            finally { battleInferenceBusy = false }
        }
    }

    private fun shop() {
        page = "shop"
        val root = layout("WORKSHOP", "Credits: $credits")
        root.addView(text("First-pass prices; economy needs playtesting.", 13f))
        addButton(root, "Weapon +1 — cost " + (120 + weapon * 95)) {
            val cost = 120 + weapon * 95
            if (credits >= cost) { credits -= cost; weapon++; save(); shop() }
            else info("NOT ENOUGH CREDITS", "Play Campaign to earn credits.")
        }
        addButton(root, "Hull +1 — cost " + (140 + hull * 110)) {
            val cost = 140 + hull * 110
            if (credits >= cost) { credits -= cost; hull++; save(); shop() }
            else info("NOT ENOUGH CREDITS", "Play Campaign to earn credits.")
        }
        addButton(root, "BACK TO COMMAND") { showMenu() }; setContentView(root)
    }
    private fun gemma() {
        if (battleModel == null) battleModelChecked = false
        page = "gemma"
        startActivity(Intent(this, GemmaWingmanActivity::class.java).apply {
            putExtra("pilot", pilot)
            putExtra("level", level)
            putExtra("exp", exp)
            putExtra("credits", credits)
            putExtra("stage", stage)
            putExtra("kills", kills)
            putExtra("weapon", weapon)
            putExtra("hull", hull)
            putExtra("next_exp", needed())
        })
    }
    private fun records() {
        info("BATTLE RECORDS", "Pilot: $pilot\nLevel: $level / 1000\nEXP: $exp / " + needed() + "\nCredits: $credits\nStage unlocked: $stage\nEnemies defeated: $kills\nWeapon upgrades: $weapon\nHull upgrades: $hull")
    }
    private fun info(title: String, message: String) {
        page = "info"
        val root = layout(title); val scroll = ScrollView(this)
        scroll.addView(text(message, 15f)); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addButton(root, "BACK") { showMenu() }; setContentView(root)
    }
    private fun battle() {
        page = "battle"
        setContentView(ShooterView(this, weapon, hull, stage, level, prefs.getString("ship_class", "NOVA") ?: "NOVA", { prompt, callback -> requestBattleGemma(prompt, callback) }) { won, xpGain, creditGain, count ->
            reward(xpGain, creditGain); kills += count
            if (won) stage++
            save()
            val title = if (won) "STAGE CLEAR" else "SHIP DISABLED"
            val outcome = if (won) "Stage completed! Next stage: $stage" else "Mission failed. Stage $stage remains unlocked."
            info(title, "$outcome\nDefeated: $count\nEXP +$xpGain\nCredits +$creditGain\nLevel $level / 1000")
        })
    }
    override fun onBackPressed() { if (page == "home") super.onBackPressed() else showMenu() }
    override fun onDestroy() { if (!battleInferenceBusy) battleModel?.let { Llama.releaseModel(it) }; battleModel = null; super.onDestroy() }
}

private class LogoView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(8, 18, 42)); val r = Random(1945)
        repeat(100) {
            p.color = if (it % 5 == 0) Color.CYAN else Color.rgb(130, 150, 190)
            c.drawCircle(r.nextFloat() * width, r.nextFloat() * height, r.nextFloat() * 2f + .5f, p)
        }
        val scale = min(width / 220f, height / 300f).coerceAtLeast(.5f)
        c.save(); c.translate(width * .5f, height * .45f); c.scale(scale, scale)
        p.color = Color.argb(65, 42, 210, 255); c.drawCircle(0f, 0f, 72f, p)
        p.color = Color.rgb(255, 176, 83); c.drawRoundRect(-15f, 28f, -7f, 52f, 4f, 4f, p); c.drawRoundRect(7f, 28f, 15f, 52f, 4f, 4f, p)
        p.color = Color.rgb(54, 204, 255)
        val wings = Path().apply { moveTo(0f, -58f); lineTo(18f, -20f); lineTo(61f, 23f); lineTo(49f, 38f); lineTo(16f, 24f); lineTo(9f, 47f); lineTo(-9f, 47f); lineTo(-16f, 24f); lineTo(-49f, 38f); lineTo(-61f, 23f); lineTo(-18f, -20f); close() }; c.drawPath(wings, p)
        p.color = Color.rgb(16, 41, 77); val hull = Path().apply { moveTo(0f, -68f); lineTo(14f, -22f); lineTo(11f, 35f); lineTo(0f, 49f); lineTo(-11f, 35f); lineTo(-14f, -22f); close() }; c.drawPath(hull, p)
        p.color = Color.WHITE; c.drawRoundRect(-5f, -43f, 5f, -13f, 5f, 5f, p); p.color = Color.rgb(100, 235, 255); c.drawRoundRect(-2f, -39f, 2f, -17f, 2f, 2f, p); c.restore()
    }
}

private class ShooterView(context: Context, private val weaponLevel: Int, private val hullLevel: Int, private val stageNumber: Int, private val pilotLevel: Int, private val shipClass: String, private val askGemma: (String, (String) -> Unit) -> Unit, val onClear: (Boolean, Int, Int, Int) -> Unit) : View(context) {
    private data class Shot(var x: Float, var y: Float, var speed: Float, var damage: Int, val homing: Boolean = false)
    private data class Enemy(var x: Float, var y: Float, var hp: Int, var speed: Float, val boss: Boolean = false, val kind: Int = 0, var fireTimer: Float = 1f)
    private data class EnemyShot(var x: Float, var y: Float, var vx: Float, var vy: Float, val damage: Int)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shots = mutableListOf<Shot>(); private val enemies = mutableListOf<Enemy>(); private val enemyShots = mutableListOf<EnemyShot>()
    private val stars = List(85) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 2f + 1f) }
    private var px = 0f; private var py = 0f; private var wx = 0f; private var wy = 0f
    private var last = System.nanoTime(); private var elapsed = 0f
    private var fire = 0f; private var gemmaFire = 0f; private var spawn = 0f
    private var shieldTimer = 0f; private var targetLockTimer = 0f; private var coverFireTimer = 0f; private var evadeTimer = 0f
    private var flashTimer = 0f; private var gemmaSkillCooldown = 0f
    private var bombCooldown = 5f; private var bombEffectTimer = 0f; private var bombX = 0f; private var bombY = 0f; private var bombRadius = 64f
    private var gemmaAutoSkillCooldown = 5f
    private var commentTime = 0f; private var comment = ""; private var gemmaAction = "ATTACK"; private var decisionTimer = .35f; private var gemmaThinking = false; private var bossFireTimer = 1.1f
    private var secondsWithoutValidDecision = 0f; private var systemTakeover = false; private var autoPilotTimer = 0f
    private val battleHistory = mutableListOf<String>()
    private var defeated = 0; private var hp = 100 + hullLevel * 20 + if (shipClass == "GUARDIAN") 40 else 0; private val maxHp = 100 + hullLevel * 20 + if (shipClass == "GUARDIAN") 40 else 0; private var done = false
    private var bossSpawned = false; private var bossCleared = false
    private val target = 30
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (px == 0f) { px = w * .5f; py = h * .78f; wx = w * .38f; wy = h * .82f }
        val now = System.nanoTime(); val dt = min(.04f, max(0f, (now - last) / 1_000_000_000f)); last = now
        if (!done) update(dt, w, h)
        c.drawColor(Color.rgb(4, 9, 27))
        stars.forEachIndexed { i, s ->
            p.color = if (i % 5 == 0) Color.CYAN else Color.rgb(110, 145, 190)
            c.drawCircle(s[0] * w, ((s[1] * h + elapsed * (18f + s[2] * 12f)) % h), s[2] * .65f, p)
        }
        p.color = Color.rgb(8, 17, 38); c.drawRoundRect(8f, 8f, w - 8f, 66f, 13f, 13f, p)
        p.color = Color.rgb(40, 69, 105); p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; c.drawRoundRect(8f, 8f, w - 8f, 66f, 13f, 13f, p); p.style = Paint.Style.FILL
        p.color = Color.WHITE; p.textSize = 14f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("SECTOR $stageNumber  •  HULL ${hp.coerceAtLeast(0)}/$maxHp", 17f, 28f, p)
        p.color = Color.rgb(25, 42, 67); c.drawRoundRect(17f, 38f, w - 17f, 45f, 4f, 4f, p)
        p.color = Color.rgb(83, 230, 255); c.drawRoundRect(17f, 38f, 17f + (w - 34f) * (hp.toFloat() / maxHp).coerceIn(0f, 1f), 45f, 4f, 4f, p)
        p.color = Color.rgb(182, 205, 232); p.textSize = 11f
        c.drawText(if (bossSpawned && !bossCleared) "BOSS ENGAGED" else "HOSTILES $defeated/$target", 17f, 59f, p)
        shots.forEach { shot ->
            p.color = Color.argb(85, 70, 235, 255); c.drawCircle(shot.x, shot.y + 5f, if (shot.homing) 12f else 9f, p)
            p.color = when { shot.homing -> Color.rgb(255, 160, 55); shot.damage > 1 -> Color.rgb(255, 205, 105); else -> Color.rgb(120, 250, 255) }
            p.strokeWidth = if (shot.homing) 8f else if (shot.damage > 1) 7f else 5f
            c.drawLine(shot.x, shot.y - 4f, shot.x, shot.y + 19f, p)
        }
        enemyShots.forEach { shot ->
            p.color = Color.argb(95, 255, 35, 85); c.drawCircle(shot.x, shot.y, 13f, p)
            p.color = Color.rgb(255, 55, 95); c.drawCircle(shot.x, shot.y, 7f, p)
            p.color = Color.rgb(255, 235, 185); c.drawCircle(shot.x, shot.y, 3f, p)
        }
        enemies.forEach { e ->
            if (e.boss) {
                p.color = Color.argb(72, 255, 50, 100); c.drawCircle(e.x, e.y, 78f, p); p.color = Color.argb(110, 255, 120, 160); c.drawCircle(e.x, e.y, 61f, p)
                p.color = Color.rgb(111, 26, 65); c.drawRoundRect(e.x - 70f, e.y - 32f, e.x + 70f, e.y + 40f, 20f, 20f, p)
                p.color = Color.rgb(255, 67, 111)
                val path = Path().apply { moveTo(e.x, e.y - 62f); lineTo(e.x + 32f, e.y - 35f); lineTo(e.x + 76f, e.y - 22f); lineTo(e.x + 60f, e.y + 4f); lineTo(e.x + 78f, e.y + 32f); lineTo(e.x + 34f, e.y + 22f); lineTo(e.x + 18f, e.y + 52f); lineTo(e.x, e.y + 36f); lineTo(e.x - 18f, e.y + 52f); lineTo(e.x - 34f, e.y + 22f); lineTo(e.x - 78f, e.y + 32f); lineTo(e.x - 60f, e.y + 4f); lineTo(e.x - 76f, e.y - 22f); lineTo(e.x - 32f, e.y - 35f); close() }; c.drawPath(path, p)
                p.color = Color.rgb(255, 178, 202); c.drawRoundRect(e.x - 50f, e.y - 13f, e.x - 30f, e.y + 28f, 6f, 6f, p); c.drawRoundRect(e.x + 30f, e.y - 13f, e.x + 50f, e.y + 28f, 6f, 6f, p); p.color = Color.rgb(255, 239, 245); c.drawCircle(e.x, e.y - 8f, 12f, p); p.color = Color.rgb(255, 48, 100); c.drawCircle(e.x, e.y - 8f, 6f, p)
                p.color = Color.rgb(45, 16, 38); c.drawRoundRect(w * .18f, 78f, w * .82f, 86f, 5f, 5f, p)
                p.color = Color.rgb(255, 75, 119); c.drawRoundRect(w * .18f, 78f, w * .18f + (w * .64f) * (e.hp.toFloat() / (220 + stageNumber * 35)).coerceIn(0f, 1f), 86f, 5f, 5f, p)
                p.color = Color.WHITE; p.textSize = 11f; p.typeface = Typeface.DEFAULT_BOLD; c.drawText("DREADNOUGHT • BOSS", w * .18f, 101f, p)
            } else {
                val emote = when (e.kind) { 0 -> "👾"; 1 -> "🛸"; 2 -> "🤖"; else -> "☄️" }
                p.textSize = 34f; p.typeface = Typeface.DEFAULT
                p.color = Color.argb(95, 255, 65, 110); c.drawCircle(e.x, e.y, 23f, p)
                p.color = Color.WHITE; c.drawText(emote, e.x - p.measureText(emote) / 2f, e.y + 12f, p)
                p.color = Color.rgb(255, 90, 125); c.drawRoundRect(e.x - 17f, e.y - 25f, e.x + 17f, e.y - 21f, 2f, 2f, p)
                p.color = Color.rgb(255, 205, 105); c.drawRoundRect(e.x - 17f, e.y - 25f, e.x - 17f + 34f * (e.hp.toFloat() / 18f).coerceIn(0f, 1f), e.y - 21f, 2f, 2f, p)
            }
        }
        val playerColor = when (shipClass) { "GUARDIAN" -> Color.rgb(102, 255, 176); "STRIKER" -> Color.rgb(255, 177, 83); else -> Color.rgb(65, 220, 255) }
        if (shieldTimer > 0f) {
            p.color = Color.argb(90, 65, 220, 255); p.style = Paint.Style.STROKE; p.strokeWidth = 5f
            c.drawCircle(px, py, 43f, p); p.style = Paint.Style.FILL
            p.color = Color.argb(35, 95, 235, 255); c.drawCircle(px, py, 39f, p)
        }
        if (evadeTimer > 0f) {
            p.color = Color.argb(85, 210, 120, 255); p.style = Paint.Style.STROKE; p.strokeWidth = 4f
            c.drawCircle(wx, wy, 38f, p); p.style = Paint.Style.FILL
        }
        ship(c, px, py, playerColor, 1.55f, false); ship(c, wx, wy, Color.rgb(195, 105, 255), 1.38f, true)
        if (bombEffectTimer > 0f) {
            val progress = (bombEffectTimer / .48f).coerceIn(0f, 1f)
            p.color = Color.argb((progress * 90f).toInt(), 255, 105, 45)
            c.drawCircle(bombX, bombY, bombRadius * (1f - progress * .25f), p)
            p.color = Color.argb((progress * 220f).toInt(), 255, 205, 105)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f
            c.drawCircle(bombX, bombY, bombRadius * (1f - progress * .18f), p)
            p.style = Paint.Style.FILL
            p.color = Color.argb((progress * 170f).toInt(), 255, 245, 200)
            c.drawCircle(bombX, bombY, max(8f, bombRadius * .18f * progress), p)
        }
        if (flashTimer > 0f) { p.color = Color.argb((flashTimer / .25f * 70f).toInt().coerceIn(0, 70), 100, 220, 255); c.drawRect(0f, 0f, w, h, p) }
        if (commentTime > 0f && comment.isNotBlank()) {
            p.textSize = 16f; p.typeface = Typeface.DEFAULT_BOLD
            val lines = comment.replace(Regex("\\s+"), " ").chunked(30).take(3)
            val textWidth = lines.maxOfOrNull { p.measureText(it) } ?: 0f
            val bubbleW = min(w * .88f, max(190f, textWidth + 28f)); val bubbleH = 24f + lines.size * 21f
            val bx = (wx - bubbleW * .52f).coerceIn(8f, max(8f, w - bubbleW - 8f)); val by = (wy - 105f).coerceAtLeast(150f)
            p.color = Color.argb(242, 17, 21, 47); c.drawRoundRect(bx, by - bubbleH, bx + bubbleW, by + 5f, 17f, 17f, p)
            p.color = Color.rgb(201, 126, 255); p.style = Paint.Style.STROKE; p.strokeWidth = 2f; c.drawRoundRect(bx, by - bubbleH, bx + bubbleW, by + 5f, 17f, 17f, p); p.style = Paint.Style.FILL
            p.color = Color.rgb(235, 222, 255); p.textSize = 16f; p.typeface = Typeface.DEFAULT_BOLD
            lines.forEachIndexed { i, line -> c.drawText(line, bx + 14f, by - bubbleH + 25f + i * 21f, p) }
        }
        if (!done) postInvalidateDelayed(16)
    }
    private fun update(dt: Float, w: Float, h: Float) {
        elapsed += dt; fire -= dt; gemmaFire -= dt; spawn -= dt; commentTime -= dt; decisionTimer -= dt; bossFireTimer -= dt
        shieldTimer = max(0f, shieldTimer - dt); targetLockTimer = max(0f, targetLockTimer - dt)
        coverFireTimer = max(0f, coverFireTimer - dt); evadeTimer = max(0f, evadeTimer - dt); flashTimer = max(0f, flashTimer - dt)
        gemmaSkillCooldown = max(0f, gemmaSkillCooldown - dt)
        gemmaAutoSkillCooldown -= dt
        bombEffectTimer = max(0f, bombEffectTimer - dt)
        bombCooldown -= dt
        if (bombCooldown <= 0f && enemies.isNotEmpty()) {
            autoAreaBomb(w, h)
            bombCooldown = 5f
        }
        secondsWithoutValidDecision += dt
        if (!systemTakeover && secondsWithoutValidDecision >= 8f) {
            systemTakeover = true; autoPilotTimer = 0f
            comment = "SYSTEM OVERRIDE: Gemma nge-lag. Biar aku yang urus dulu!"; commentTime = 4.5f
            battleHistory.add("System mengambil alih kendali Gemma.")
        }
        val danger = enemies.firstOrNull { kotlin.math.abs(it.x - wx) < 72f && it.y > wy - 145f && !it.boss }
        val targetEnemy = if (gemmaAction == "BOSS") enemies.firstOrNull { it.boss }
            else enemies.minByOrNull { kotlin.math.abs(it.x - wx) + kotlin.math.abs(it.y - wy) * .55f }
        if (gemmaAutoSkillCooldown <= 0f && enemies.isNotEmpty()) {
            val targetX = targetEnemy?.x ?: wx
            repeat(3) { i ->
                val spread = (i - 1) * 14f
                shots.add(Shot(wx + spread + (targetX - wx) * .08f, wy - 20f, -590f, 3 + weaponLevel / 2))
            }
            gemmaAutoSkillCooldown = 5f
            if (!systemTakeover && commentTime <= 0f) { comment = "Gemma: COVER FIRE!"; commentTime = 2.4f }
            battleHistory.add("Gemma auto Cover Fire.")
        }
        if (decisionTimer <= 0f && !gemmaThinking) {
            gemmaThinking = true; decisionTimer = 4.2f
            val nearest = targetEnemy?.let { if (it.boss) "DREADNOUGHT boss HP " + it.hp else "enemy HP " + it.hp + ", x=" + it.x.toInt() + ", y=" + it.y.toInt() } ?: "tidak ada target dekat"
            val unlocked = buildList {
                if (pilotLevel >= 1 || stageNumber >= 1) add("COVER_FIRE")
                if (pilotLevel >= 2 || stageNumber >= 2) add("TARGET_LOCK")
                if (pilotLevel >= 4 || stageNumber >= 4) add("EVADE")
                if (pilotLevel >= 7 || stageNumber >= 7) add("COORDINATED")
            }.joinToString(",")
            val state = "Stage=" + stageNumber + "; pilot level=" + pilotLevel + "; hull=" + hp + "/" + maxHp + "; kills=" + defeated + "/" + target + "; action=" + gemmaAction + "; danger=" + (danger != null) + "; target=" + nearest + "; boss=" + (bossSpawned && !bossCleared) + "; Gemma skills unlocked=" + unlocked + "; skill cooldown=" + String.format(java.util.Locale.US, "%.1f", gemmaSkillCooldown) + ". Pilih keputusan valid dan SAY pendek."
            askGemma(state) { raw ->
                gemmaThinking = false
                decisionTimer = 4.2f
                val action = Regex("(?i)ACTION\\s*[:=\\-]?\\s*(ATTACK|DODGE|COVER|BOSS|TARGET_LOCK|COVER_FIRE|EVADE|COORDINATED|SKILL)").find(raw)?.groupValues?.get(1)?.uppercase()
                if (action != null) {
                    secondsWithoutValidDecision = 0f
                    if (systemTakeover) {
                        systemTakeover = false
                        comment = "Gemma is back! 😎🚀"; commentTime = 4.5f
                        battleHistory.add("Gemma kembali mengambil kendali AI.")
                    } else {
                        val say = Regex("(?im)^\\s*SAY\\s*:\\s*(.+)$").find(raw)?.groupValues?.get(1)?.trim()
                        if (say != null && say.isNotBlank()) { comment = say.take(58); commentTime = 4.5f; battleHistory.add("Gemma: " + comment); if (battleHistory.size > 12) battleHistory.removeAt(0) }
                    }
                    when (action) {
                        "TARGET_LOCK" -> if (isGemmaSkillUnlocked(1)) activateGemmaSkill("TARGET_LOCK") else gemmaAction = "ATTACK"
                        "COVER_FIRE" -> if (isGemmaSkillUnlocked(0)) activateGemmaSkill("COVER_FIRE") else gemmaAction = "ATTACK"
                        "EVADE" -> if (isGemmaSkillUnlocked(2)) activateGemmaSkill("EVADE") else gemmaAction = "ATTACK"
                        "COORDINATED", "SKILL" -> if (isGemmaSkillUnlocked(3) && gemmaSkillCooldown <= 0f) activateGemmaSkill("COORDINATED") else if (isGemmaSkillUnlocked(0) && gemmaSkillCooldown <= 0f) activateGemmaSkill("COVER_FIRE") else gemmaAction = "ATTACK"
                        else -> gemmaAction = action
                    }
                }
                postInvalidateOnAnimation()
            }
        }
        if (systemTakeover) {
            autoPilotTimer -= dt
            if (autoPilotTimer <= 0f) {
                autoPilotTimer = .72f
                when {
                    danger != null -> gemmaAction = "DODGE"
                    bossSpawned && !bossCleared -> gemmaAction = "BOSS"
                    gemmaSkillCooldown <= 0f && isGemmaSkillUnlocked(3) -> activateGemmaSkill("COORDINATED")
                    gemmaSkillCooldown <= 0f && isGemmaSkillUnlocked(0) -> activateGemmaSkill("COVER_FIRE")
                    else -> gemmaAction = "ATTACK"
                }
            }
        }
        if (coverFireTimer <= 0f && gemmaAction == "COVER_FIRE") gemmaAction = "ATTACK"
        if (evadeTimer <= 0f && gemmaAction == "EVADE") gemmaAction = "ATTACK"
        val desiredX = when (gemmaAction) {
            "COVER" -> px - w * .14f
            "DODGE", "EVADE" -> if (danger != null) wx + if (danger.x < wx) w * .18f else -w * .18f else targetEnemy?.x ?: (px - w * .14f)
            else -> targetEnemy?.x ?: (px - w * .18f)
        }
        wx += (desiredX.coerceIn(w * .12f, w * .88f) - wx) * min(1f, dt * if (evadeTimer > 0f || gemmaAction == "DODGE") 5f else 2.8f)
        val desiredY = if (gemmaAction == "DODGE" || gemmaAction == "EVADE" || danger != null) h * .57f else h * .73f
        wy += (desiredY - wy) * min(1f, dt * 2.4f)
        wx = wx.coerceIn(24f, w - 24f); wy = wy.coerceIn(h * .43f, h - 125f)
        val damage = 1 + weaponLevel / 2 + if (shipClass == "STRIKER") 1 else 0
        if (fire <= 0f) { shots.add(Shot(px, py - 22f, -610f, damage)); fire = if (shipClass == "STRIKER") .18f else .24f }
        if (gemmaFire <= 0f) {
            val targetX = targetEnemy?.x ?: wx
            val gemmaDamage = damage + if (targetLockTimer > 0f) 2 else 0
            if (coverFireTimer > 0f || gemmaAction == "COVER_FIRE") {
                repeat(3) { i -> shots.add(Shot(wx + (i - 1) * 12f, wy - 18f, -600f, gemmaDamage + 1)) }
                gemmaFire = .24f
            } else {
                shots.add(Shot(wx + (targetX - wx) * .08f, wy - 15f, -560f, gemmaDamage))
                gemmaFire = .38f
            }
        }
        if (spawn <= 0f && enemies.size < 8 && defeated < target) {
            val typeHp = (3 + stageNumber / 3 + weaponLevel / 3).coerceAtMost(16)
            val kind = Random.nextInt(4)
            val hpForType = typeHp + if (kind == 2) 2 else 0
            enemies.add(Enemy(Random.nextFloat() * w * .76f + w * .12f, -28f, hpForType, (if (kind == 1) 175f else 105f) + stageNumber * 2f + Random.nextFloat() * 65f, false, kind, .65f + Random.nextFloat() * 1.2f))
            spawn = max(.34f, .82f - defeated * .009f)
        }
        if (defeated >= target && enemies.isEmpty() && !bossSpawned) {
            val bossHp = 220 + stageNumber * 35
            enemies.add(Enemy(w * .5f, h * .19f, bossHp, 0f, true, 4, 1f)); bossSpawned = true
            battleHistory.add("Dreadnought boss muncul setelah " + defeated + " kills."); decisionTimer = 0f
        }
        enemies.forEach { e ->
            if (e.boss) {
                e.x = (w * .5f + sin(elapsed * .62f) * w * .21f).coerceIn(w * .25f, w * .75f)
                if (bossFireTimer <= 0f) {
                    val phaseTwo = e.hp < (220 + stageNumber * 35) * .48f
                    val count = if (phaseTwo) 7 else 3
                    val base = atan2(py - e.y, px - e.x)
                    val speed = if (phaseTwo) 255f else 210f
                    for (i in 0 until count) {
                        val angle = base + (i - (count - 1) / 2f) * if (phaseTwo) .19f else .24f
                        enemyShots.add(EnemyShot(e.x, e.y + 44f, cos(angle) * speed, sin(angle) * speed, if (phaseTwo) 13 else 9))
                    }
                    bossFireTimer = if (phaseTwo) .78f else 1.28f
                    battleHistory.add(if (phaseTwo) "Boss melepas salvo fase dua" else "Boss menembak ke arah pilot")
                }
            } else {
                e.fireTimer -= dt
                when (e.kind) {
                    1 -> e.x += sin(elapsed * 3.2f + e.y * .012f) * 82f * dt
                    2 -> e.x += sin(elapsed * 1.8f + e.x * .02f) * 42f * dt
                    3 -> e.x += cos(elapsed * 2.7f + e.y * .01f) * 68f * dt
                }
                e.x = e.x.coerceIn(22f, w - 22f)
                e.y += e.speed * dt
                if (e.fireTimer <= 0f && e.y > 40f && e.y < h * .78f) {
                    val dx = px - e.x
                    val dy = py - e.y
                    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val aimed = e.kind == 2 || e.kind == 3
                    val speed = if (e.kind == 1) 250f else 205f
                    enemyShots.add(EnemyShot(e.x, e.y + 13f, if (aimed) dx / len * speed else 0f, if (aimed) dy / len * speed else speed, if (e.kind == 2) 8 else 5))
                    e.fireTimer = when (e.kind) { 0 -> 2.4f; 1 -> 1.65f; 2 -> 1.35f; else -> 1.8f } + Random.nextFloat() * .45f
                }
                if (e.y > h + 20f) { e.hp = 0; hp = max(0, hp - 5); battleHistory.add("Musuh lolos; hull turun 5.") }
            }
        }
        enemyShots.forEach { it.x += it.vx * dt; it.y += it.vy * dt }
        enemyShots.removeAll { it.x < -20f || it.x > w + 20f || it.y < -20f || it.y > h + 20f }
        val bulletHits = enemyShots.filter { abs(it.x - px) < 27f && abs(it.y - py) < 31f }
        if (bulletHits.isNotEmpty()) {
            if (shieldTimer <= 0f) { hp = max(0, hp - bulletHits.sumOf { it.damage }); battleHistory.add("Peluru musuh mengenai pesawat pemain") }
            else battleHistory.add("Shield Pulse menahan tembakan musuh.")
            enemyShots.removeAll(bulletHits.toSet())
        }
        shots.filter { it.homing }.forEach { shot ->
            val target = enemies.minByOrNull { abs(it.x - shot.x) + abs(it.y - shot.y) * .45f }
            if (target != null) shot.x += (target.x - shot.x) * min(1f, dt * 4.5f)
            shot.y += shot.speed * dt
        }
        shots.filter { !it.homing }.forEach { it.y += it.speed * dt }
        shots.removeAll { it.y < -30f }
        val hit = mutableSetOf<Shot>(); val dead = mutableSetOf<Enemy>()
        enemies.forEach { e -> shots.forEach { shot ->
            val hitRadius = if (e.boss) 78f else 28f
            if (abs(shot.x - e.x) < hitRadius && abs(shot.y - e.y) < if (e.boss) 58f else 30f) {
                e.hp -= shot.damage; hit.add(shot)
                if (e.hp <= 0) { dead.add(e); if (e.boss) { bossCleared = true; battleHistory.add("Dreadnought boss dikalahkan."); decisionTimer = 0f } else { defeated++; if (defeated % 5 == 0) battleHistory.add("Total kills: " + defeated) } }
            }
        } }
        shots.removeAll(hit); enemies.removeAll(dead); enemies.removeAll { it.hp <= 0 }
        if (hp <= 0) { done = true; onClear(false, max(10, defeated * 5), max(15, defeated * 4), defeated + if (bossCleared) 1 else 0) }
        else if (defeated >= target && bossCleared && enemies.isEmpty()) { done = true; onClear(true, 180 + stageNumber * 12 + defeated * 6, 150 + stageNumber * 8 + defeated * 5, defeated + 1) }
    }
    private fun isGemmaSkillUnlocked(i: Int) = when (i) {
        0 -> pilotLevel >= 1 || stageNumber >= 1
        1 -> pilotLevel >= 2 || stageNumber >= 2
        2 -> pilotLevel >= 4 || stageNumber >= 4
        else -> pilotLevel >= 7 || stageNumber >= 7
    }
    private fun activateGemmaSkill(which: String) {
        if (gemmaSkillCooldown > 0f) { gemmaAction = "ATTACK"; return }
        gemmaSkillCooldown = 5f
        when (which) {
            "TARGET_LOCK" -> { targetLockTimer = 3.2f; if (!systemTakeover && commentTime <= 0f) { comment = "Target lock! Aku tandai targetnya."; commentTime = 3.5f }; gemmaAction = "ATTACK" }
            "COVER_FIRE" -> { coverFireTimer = 3.2f; if (!systemTakeover && commentTime <= 0f) { comment = "Cover fire! Jangan sampai lolos!"; commentTime = 3.5f }; gemmaAction = "COVER_FIRE" }
            "EVADE" -> { evadeTimer = 2.5f; if (!systemTakeover && commentTime <= 0f) { comment = "Evasive maneuver!"; commentTime = 3.5f }; gemmaAction = "EVADE" }
            "COORDINATED" -> {
                val target = enemies.firstOrNull { it.boss } ?: enemies.minByOrNull { abs(it.x - wx) + abs(it.y - wy) * .5f }
                val center = target?.x ?: width.toFloat() * .5f
                repeat(5) { i -> val spread = (i - 2) * 15f; shots.add(Shot(px + spread, py - 18f, -680f, 5 + weaponLevel / 2)); shots.add(Shot(center + spread, wy - 18f, -700f, 5 + weaponLevel / 2)) }
                if (!systemTakeover && commentTime <= 0f) { comment = "COORDINATED STRIKE!"; commentTime = 3.8f }
                gemmaAction = "ATTACK"
            }
        }
        battleHistory.add("Gemma skill: $which")
    }
    private fun autoAreaBomb(w: Float, h: Float) {
        val target = enemies.minByOrNull { abs(it.x - px) + abs(it.y - py) * .55f }
        bombX = target?.x ?: px
        bombY = target?.y ?: (py - h * .24f).coerceAtLeast(100f)
        bombRadius = min(max(w, h) * .48f, 64f + (pilotLevel / 20) * 14f)
        bombEffectTimer = .48f
        flashTimer = .12f
        val damage = 12 + pilotLevel / 5 + weaponLevel
        val hit = enemies.filter { enemy ->
            val dx = enemy.x - bombX; val dy = enemy.y - bombY
            kotlin.math.sqrt(dx * dx + dy * dy) <= bombRadius + if (enemy.boss) 32f else 0f
        }
        val dead = hit.filter { it.hp <= damage }
        hit.forEach { it.hp -= damage }
        dead.forEach { enemy ->
            if (enemy.boss) { bossCleared = true; bossSpawned = true; battleHistory.add("Auto Area Bomb menghancurkan boss.") }
            else defeated++
        }
        enemies.removeAll(dead.toSet())
        enemyShots.removeAll { shot ->
            val dx = shot.x - bombX; val dy = shot.y - bombY
            kotlin.math.sqrt(dx * dx + dy * dy) <= bombRadius
        }
        comment = "AUTO AREA BOMB!"; commentTime = 1.8f
        battleHistory.add("Auto Area Bomb • radius ${bombRadius.toInt()} • damage $damage")
        postInvalidateOnAnimation()
    }
    private fun ship(c: Canvas, x: Float, y: Float, color: Int, scale: Float, wingman: Boolean) {
        c.save(); c.translate(x, y); c.scale(scale, scale)
        p.color = Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)); c.drawCircle(0f, 0f, 25f, p)
        p.color = color
        val wings = Path().apply { moveTo(0f, -38f); lineTo(14f, -14f); lineTo(42f, 13f); lineTo(38f, 25f); lineTo(13f, 16f); lineTo(8f, 31f); lineTo(-8f, 31f); lineTo(-13f, 16f); lineTo(-38f, 25f); lineTo(-42f, 13f); lineTo(-14f, -14f); close() }; c.drawPath(wings, p)
        p.color = Color.rgb(18, 33, 64); val hull = Path().apply { moveTo(0f, -48f); lineTo(12f, -15f); lineTo(10f, 22f); lineTo(0f, 34f); lineTo(-10f, 22f); lineTo(-12f, -15f); close() }; c.drawPath(hull, p); if (wingman) { p.color = Color.rgb(248, 220, 255); c.drawCircle(0f, -1f, 6f, p); p.color = Color.rgb(170, 75, 255); c.drawCircle(0f, -1f, 3f, p) } else { p.color = Color.WHITE; c.drawCircle(0f, -5f, 4f, p) }; c.restore()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                px = e.x.coerceIn(28f, width - 28f)
                py = e.y.coerceIn(height * .35f, height - 40f)
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }
}
