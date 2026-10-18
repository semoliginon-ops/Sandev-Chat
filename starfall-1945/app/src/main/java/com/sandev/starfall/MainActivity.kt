package com.sandev.starfall

import android.app.Activity
import android.os.Bundle
import android.content.Context
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
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : Activity() {
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
            setPadding(dp(20), dp(20), dp(20), dp(16)); setBackgroundColor(Color.rgb(5, 10, 26))
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
        backgroundTintList = ColorStateList.valueOf(Color.rgb(24, 76, 125))
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
        root.addView(LogoView(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("Classic vertical shooter • Developer: SANDEV", 13f))
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
        val root = layout("STARFALL COMMAND", "$pilot • LV $level • EXP $exp/" + needed() + " • Credits $credits")
        val scroll = ScrollView(this)
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(items); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addButton(items, "▶ CAMPAIGN — STAGE $stage") { battle() }
        addButton(items, "HANGAR & SHIP STATUS") { info("HANGAR", "NOVA-01 fighter\nWeapon upgrade: $weapon\nHull upgrade: $hull\nGemma: AI wingman concept\n\nLoadouts and cosmetics are planned for later.") }
        addButton(items, "WORKSHOP / SHOP") { shop() }
        addButton(items, "SKILL LAB") { info("SKILL LAB", "PLAYER: Laser Burst, Shield Pulse, Homing Missiles\nGEMMA: Cover Fire, Evasive Maneuver, Coordinated Strike\n\nPrototype auto skill burst has a 5-second cooldown. Full skill trees are not implemented yet.") }
        addButton(items, "GEMMA — CHAT & PILOT PROFILE") { gemma() }
        addButton(items, "BATTLE RECORDS") { records() }
        addButton(items, "MISSIONS & SECTOR MAP") { info("CAMPAIGN MAP", "Sector 1: FRONTIER\nNext stage: $stage\n\nThis prototype has a repeatable stage loop. Unique sectors, formations and bosses are future work.") }
        addButton(items, "SETTINGS") { info("SETTINGS", "Offline prototype • Portrait controls\nSound, touch sensitivity and visual settings are planned.") }
        addButton(root, "START BATTLE") { battle() }
        setContentView(root)
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
        page = "gemma"
        val root = layout("GEMMA • WINGMAN", "Level $level • Campaign stage $stage • $kills kills")
        root.addView(text("Gemma profile and saved game stats are visible here. The local GGUF chat runtime is not connected in this first prototype.", 15f))
        root.addView(text("Planned: chat with Gemma in the menu using your pilot profile, her profile, ship upgrades and battle history. During battle, manual chat stays hidden.", 14f))
        addButton(root, "VIEW GAME MEMORY") { records() }
        addButton(root, "BACK TO COMMAND") { showMenu() }; setContentView(root)
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
        setContentView(ShooterView(this, weapon, hull, stage) { won, xpGain, creditGain, count ->
            reward(xpGain, creditGain); kills += count
            if (won) stage++
            save()
            val title = if (won) "STAGE CLEAR" else "SHIP DISABLED"
            val outcome = if (won) "Stage completed! Next stage: $stage" else "Mission failed. Stage $stage remains unlocked."
            info(title, "$outcome\nDefeated: $count\nEXP +$xpGain\nCredits +$creditGain\nLevel $level / 1000")
        })
    }
    override fun onBackPressed() { if (page == "home") super.onBackPressed() else showMenu() }
}

private class LogoView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(8, 18, 42)); val r = Random(1945)
        repeat(100) {
            p.color = if (it % 5 == 0) Color.CYAN else Color.rgb(130, 150, 190)
            c.drawCircle(r.nextFloat() * width, r.nextFloat() * height, r.nextFloat() * 2f + .5f, p)
        }
        p.color = Color.rgb(40, 200, 255)
        val path = Path().apply {
            moveTo(width * .5f, height * .2f); lineTo(width * .63f, height * .54f)
            lineTo(width * .5f, height * .46f); lineTo(width * .37f, height * .54f); close()
        }
        c.drawPath(path, p)
    }
}

private class ShooterView(context: Context, private val weaponLevel: Int, private val hullLevel: Int, private val stageNumber: Int, val onClear: (Boolean, Int, Int, Int) -> Unit) : View(context) {
    private data class Shot(var x: Float, var y: Float, var speed: Float, var damage: Int)
    private data class Enemy(var x: Float, var y: Float, var hp: Int, var speed: Float)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shots = mutableListOf<Shot>(); private val enemies = mutableListOf<Enemy>()
    private val stars = List(85) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 2f + 1f) }
    private var px = 0f; private var py = 0f; private var wx = 0f; private var wy = 0f
    private var last = System.nanoTime(); private var elapsed = 0f
    private var fire = 0f; private var spawn = 0f; private var skill = 0f
    private var commentTime = 3f; private var comment = "Gemma: Aku di sisimu! Ayo maju!"
    private var defeated = 0; private var hp = 100 + hullLevel * 20; private var done = false
    private val target = min(60, 24 + stageNumber / 3)
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
        p.color = Color.rgb(20, 44, 76); c.drawRect(0f, 0f, w, 76f, p)
        p.color = Color.WHITE; p.textSize = 15f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("STAGE $stageNumber • HULL $hp%", 14f, 26f, p); c.drawText("ENEMIES $defeated/$target", 14f, 50f, p)
        shots.forEach { p.color = Color.CYAN; p.strokeWidth = if (it.damage > 1) 5f else 3f; c.drawLine(it.x, it.y, it.x, it.y + 13f, p) }
        enemies.forEach { e ->
            p.color = Color.rgb(255, 70, 100)
            val path = Path().apply { moveTo(e.x, e.y + 20f); lineTo(e.x + 19f, e.y - 13f); lineTo(e.x, e.y - 5f); lineTo(e.x - 19f, e.y - 13f); close() }
            c.drawPath(path, p)
        }
        ship(c, px, py, Color.rgb(65, 220, 255), 1f); ship(c, wx, wy, Color.rgb(195, 105, 255), .72f)
        if (commentTime > 0) {
            p.color = Color.argb(225, 17, 31, 60); c.drawRoundRect(12f, 90f, w - 12f, 142f, 14f, 14f, p)
            p.color = Color.rgb(215, 190, 255); p.textSize = 13f; c.drawText(comment.take(52), 22f, 121f, p)
        }
        if (!done) postInvalidateDelayed(16)
    }
    private fun update(dt: Float, w: Float, h: Float) {
        elapsed += dt; fire -= dt; spawn -= dt; skill -= dt; commentTime -= dt
        if (fire <= 0f) { val damage = 1 + weaponLevel / 2; shots.add(Shot(px, py - 22f, -610f, damage)); shots.add(Shot(wx, wy - 15f, -560f, damage)); fire = .24f }
        if (skill <= 0f) {
            repeat(3) { i -> shots.add(Shot(wx + (i - 1) * 17f, wy - 18f, -650f, 2)) }
            comment = listOf("Gemma: Aku kunci target!", "Gemma: Cover fire, sekarang!", "Gemma: Serangan gabungan!", "Gemma: Awas, musuh di depan!").random()
            commentTime = 2.3f; skill = 5f
        }
        if (spawn <= 0f && enemies.size < 9 && defeated < target) {
            enemies.add(Enemy(Random.nextFloat() * w * .76f + w * .12f, -24f, (2 + stageNumber / 4 + weaponLevel / 3).coerceAtMost(12), 130f + stageNumber * 2f + Random.nextFloat() * 90f))
            spawn = max(.36f, .9f - defeated * .012f)
        }
        shots.forEach { it.y += it.speed * dt }
        enemies.forEach { e -> e.y += e.speed * dt; if (e.y > h + 20f) { e.hp = 0; hp = max(0, hp - 5); comment = "Gemma: Ada musuh yang lolos!"; commentTime = 2f } }
        shots.removeAll { it.y < -30f }
        val hit = mutableSetOf<Shot>(); val dead = mutableSetOf<Enemy>()
        enemies.forEach { e -> shots.forEach { s ->
            if (kotlin.math.abs(s.x - e.x) < 28f && kotlin.math.abs(s.y - e.y) < 30f) {
                e.hp -= s.damage; hit.add(s); if (e.hp <= 0) { dead.add(e); defeated++ }
            }
        } }
        shots.removeAll(hit); enemies.removeAll(dead); enemies.removeAll { it.hp <= 0 }
        if (hp <= 0) { done = true; onClear(false, max(10, defeated * 5), max(15, defeated * 4), defeated) }
        else if (defeated >= target && enemies.isEmpty()) { done = true; onClear(true, 120 + stageNumber * 8 + defeated * 5, 100 + stageNumber * 6 + defeated * 4, defeated) }
    }
    private fun ship(c: Canvas, x: Float, y: Float, color: Int, scale: Float) {
        c.save(); c.translate(x, y); c.scale(scale, scale)
        p.color = Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)); c.drawCircle(0f, 0f, 25f, p)
        p.color = color
        val path = Path().apply { moveTo(0f, -26f); lineTo(19f, 16f); lineTo(0f, 9f); lineTo(-19f, 16f); close() }
        c.drawPath(path, p); p.color = Color.WHITE; c.drawCircle(0f, -5f, 4f, p); c.restore()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            px = e.x.coerceIn(28f, width - 28f); py = e.y.coerceIn(height * .42f, height - 45f)
            wx = (px - width * .15f).coerceIn(24f, width - 24f); wy = (py + 26f).coerceIn(height * .45f, height - 35f)
        }
        return true
    }
}
