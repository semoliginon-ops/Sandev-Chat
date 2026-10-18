package com.sandev.starfall

import android.app.Activity
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
    private data class Enemy(var x: Float, var y: Float, var hp: Int, var speed: Float, val boss: Boolean = false)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shots = mutableListOf<Shot>(); private val enemies = mutableListOf<Enemy>()
    private val stars = List(85) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 2f + 1f) }
    private var px = 0f; private var py = 0f; private var wx = 0f; private var wy = 0f
    private var last = System.nanoTime(); private var elapsed = 0f
    private var fire = 0f; private var spawn = 0f; private var skill = 0f
    private var commentTime = 3f; private var comment = "Gemma: Aku di sisimu! Ayo maju!"
    private var defeated = 0; private var hp = 100 + hullLevel * 20; private val maxHp = 100 + hullLevel * 20; private var done = false
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
        shots.forEach { p.color = if (it.damage > 1) Color.rgb(255, 205, 105) else Color.CYAN; p.strokeWidth = if (it.damage > 1) 5f else 3f; c.drawLine(it.x, it.y, it.x, it.y + 13f, p) }
        enemies.forEach { e ->
            if (e.boss) {
                p.color = Color.argb(75, 255, 70, 125); c.drawCircle(e.x, e.y, 42f, p)
                p.color = Color.rgb(255, 67, 111)
                val path = Path().apply { moveTo(e.x, e.y + 35f); lineTo(e.x + 38f, e.y + 4f); lineTo(e.x + 30f, e.y - 27f); lineTo(e.x + 10f, e.y - 17f); lineTo(e.x, e.y - 38f); lineTo(e.x - 10f, e.y - 17f); lineTo(e.x - 30f, e.y - 27f); lineTo(e.x - 38f, e.y + 4f); close() }; c.drawPath(path, p)
                p.color = Color.WHITE; c.drawCircle(e.x, e.y, 6f, p)
                p.color = Color.rgb(45, 16, 38); c.drawRoundRect(w * .18f, 78f, w * .82f, 86f, 5f, 5f, p)
                p.color = Color.rgb(255, 75, 119); c.drawRoundRect(w * .18f, 78f, w * .18f + (w * .64f) * (e.hp.toFloat() / (220 + stageNumber * 35)).coerceIn(0f, 1f), 86f, 5f, 5f, p)
                p.color = Color.WHITE; p.textSize = 11f; p.typeface = Typeface.DEFAULT_BOLD; c.drawText("DREADNOUGHT • BOSS", w * .18f, 101f, p)
            } else {
                p.color = if (stageNumber % 3 == 0) Color.rgb(255, 155, 92) else Color.rgb(255, 70, 100)
                val path = Path().apply { moveTo(e.x, e.y + 20f); lineTo(e.x + 19f, e.y - 13f); lineTo(e.x, e.y - 5f); lineTo(e.x - 19f, e.y - 13f); close() }; c.drawPath(path, p)
                p.color = Color.rgb(255, 190, 205); c.drawCircle(e.x, e.y, 3f, p)
            }
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
        // Gemma Player AI: tracks the most dangerous target and repositions to avoid incoming lanes.
        val targetEnemy = enemies.minByOrNull { kotlin.math.abs(it.x - wx) + kotlin.math.abs(it.y - wy) * .55f }
        val desiredX = targetEnemy?.x ?: (px - w * .18f)
        val danger = enemies.firstOrNull { kotlin.math.abs(it.x - wx) < 42f && it.y > wy - 90f }
        wx += (desiredX.coerceIn(w * .12f, w * .88f) - wx) * min(1f, dt * 2.8f)
        val desiredY = if (danger != null) h * .60f else h * .77f
        wy += (desiredY - wy) * min(1f, dt * 1.8f)
        wx = wx.coerceIn(24f, w - 24f); wy = wy.coerceIn(h * .48f, h - 35f)
        if (fire <= 0f) { val damage = 1 + weaponLevel / 2; shots.add(Shot(px, py - 22f, -610f, damage)); shots.add(Shot(wx, wy - 15f, -560f, damage)); fire = .24f }
        if (skill <= 0f) {
            val burstX = targetEnemy?.x ?: wx
            repeat(3) { i -> shots.add(Shot(burstX + (i - 1) * 17f, wy - 18f, -650f, 2 + weaponLevel / 3)) }
            comment = when { enemies.any { it.boss } -> "Gemma Player: fokus boss, gunakan burst!"; danger != null -> "Gemma Player: manuver menghindar!"; else -> listOf("Gemma Player: target dikunci!", "Gemma Player: cover fire!", "Gemma Player: serangan gabungan!").random() }
            commentTime = 2.3f; skill = if (enemies.any { it.boss }) 3.5f else 5f
        }
        if (spawn <= 0f && enemies.size < 8 && defeated < target) {
            val typeHp = (3 + stageNumber / 3 + weaponLevel / 3).coerceAtMost(16)
            enemies.add(Enemy(Random.nextFloat() * w * .76f + w * .12f, -24f, typeHp, 120f + stageNumber * 2f + Random.nextFloat() * 80f))
            spawn = max(.34f, .82f - defeated * .009f)
        }
        if (defeated >= target && enemies.isEmpty() && !bossSpawned) {
            val bossHp = 220 + stageNumber * 35
            enemies.add(Enemy(w * .5f, h * .19f, bossHp, 0f, true)); bossSpawned = true
            comment = "Gemma Player: BOSS terdeteksi!"; commentTime = 3.5f
        }
        enemies.forEach { e ->
            if (e.boss) { e.x = (w * .5f + kotlin.math.sin(elapsed * .9f) * w * .28f).coerceIn(w * .2f, w * .8f) }
            else { e.y += e.speed * dt; if (e.y > h + 20f) { e.hp = 0; hp = max(0, hp - 5); comment = "Gemma Player: musuh lolos!"; commentTime = 2f } }
        }
        shots.forEach { it.y += it.speed * dt }
        shots.removeAll { it.y < -30f }
        val hit = mutableSetOf<Shot>(); val dead = mutableSetOf<Enemy>()
        enemies.forEach { e -> shots.forEach { shot ->
            val hitRadius = if (e.boss) 48f else 28f
            if (kotlin.math.abs(shot.x - e.x) < hitRadius && kotlin.math.abs(shot.y - e.y) < if (e.boss) 44f else 30f) {
                e.hp -= shot.damage; hit.add(shot)
                if (e.hp <= 0) { dead.add(e); if (e.boss) { bossCleared = true; comment = "Gemma Player: boss tumbang!"; commentTime = 3f } else defeated++ }
            }
        } }
        shots.removeAll(hit); enemies.removeAll(dead); enemies.removeAll { it.hp <= 0 }
        if (hp <= 0) { done = true; onClear(false, max(10, defeated * 5), max(15, defeated * 4), defeated + if (bossCleared) 1 else 0) }
        else if (defeated >= target && bossCleared && enemies.isEmpty()) { done = true; onClear(true, 180 + stageNumber * 12 + defeated * 6, 150 + stageNumber * 8 + defeated * 5, defeated + 1) }
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
            /* Gemma Player repositions autonomously using its tactical AI. */
        }
        return true
    }
}
