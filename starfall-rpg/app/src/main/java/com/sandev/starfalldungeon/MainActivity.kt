package com.sandev.starfalldungeon

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.SoundPool
import android.app.AlertDialog
import android.widget.EditText
import android.text.InputType
import org.json.JSONObject
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = 5894 or 1024 or 512
        setContentView(ForestGameView(this))
    }
}

private data class Mob(
    var x: Float, var y: Float, val kind: Int, var hp: Float, val maxHp: Float,
    var attack: Float = 1f, var windup: Float = 0f, var alive: Boolean = true,
    var hitEmoji: String = "", var hitEmojiTime: Float = 0f,
    var displayEmoji: String = ""
)

private class ForestGameView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("starfall_rpg_save", Context.MODE_PRIVATE)
    private val paint = Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val soundPool = SoundPool.Builder().setMaxStreams(8).build()
    private val soundIds = mutableMapOf<String, Int>()
    private var urbanSheet: Bitmap? = null
    private var rpgSheet: Bitmap? = null
    private var heroSheet: Bitmap? = null
    private var gemmaSheet: Bitmap? = null
    private var pigSheet: Bitmap? = null
    private var areaBackdrop: Bitmap? = null
    private var activeAreaIndex = 0
    private val areaKeys = listOf("forest", "rawa", "pantai", "pegunungan", "salju", "gurun", "vulkanik")
    private val areaNames = listOf("Hutan", "Rawa", "Pantai", "Pegunungan", "Salju", "Gurun", "Vulkanik")
    private var footstepTimer = 0f
    private data class Effect(var x: Float, var y: Float, var tx: Float, var ty: Float, var life: Float, val kind: Int)
    private val effects = mutableListOf<Effect>()
    private var hitFlash = 0f
    private var lastPlayerX = 470f
    private var lastPlayerY = 285f
    private val random = Random(System.currentTimeMillis())
    private val trees = listOf(
        RectF(110f,105f,155f,160f), RectF(250f,95f,300f,150f), RectF(430f,120f,480f,175f),
        RectF(670f,105f,720f,160f), RectF(820f,220f,865f,275f), RectF(170f,305f,215f,360f),
        RectF(410f,345f,460f,400f), RectF(620f,330f,670f,385f)
    )
    private val mobs = mutableListOf<Mob>()
    private var x = 470f; private var y = 285f
    private var gemmaX = 425f; private var gemmaY = 320f
    private var hp = 100f; private var maxHp = 100f
    private var mana = 100f; private var maxMana = 100f
    private var gemmaHp = 100f; private var gemmaMaxHp = 100f
    private var level = prefs.getInt("level", 1).coerceIn(1, 100)
    private var exp = prefs.getInt("exp", 0).coerceAtLeast(0)
    private var gold = prefs.getInt("gold", 0).coerceAtLeast(0)
    private var kills = prefs.getInt("kills", 0).coerceAtLeast(0)
    private var campaignStage = prefs.getInt("campaign_stage", 1).coerceIn(1, 50)
    private var unlockedStage = prefs.getInt("campaign_unlocked_stage", 1).coerceIn(1, 50)
    private var stageKills = prefs.getInt("campaign_stage_kills", 0).coerceIn(0, 30)
    private var gemmaLevel = prefs.getInt("gemma_level", 1).coerceIn(1, 100)
    private var gemmaExp = prefs.getInt("gemma_exp", 0).coerceAtLeast(0)
    private var damage = 14f + (level - 1) * 2f
    private var attackTimer = 0f; private var gemmaTimer = 0f
    private var gemmaChat = "Ayo, kita pasti bisa! 💖"
    private var pendingGemmaChat: String? = null
    private var gemmaChatTime = 0f
    private var gemmaChatTimer = 5f
    private var dodgeCooldown = 0f; private var dodgeTime = 0f; private var invuln = 0f
    private var skillCooldown = 0f; private var moveX = 0f; private var moveY = 0f
    private var stickId = -1; private var command = "FOLLOW"
    private var message = "Hutan Sunyi: berburu untuk EXP dan gold"
    private var messageTime = 5f; private var clock = 0f; private var screenShake = 0f
    private var bossSpawned = false; private var bossCleared = false
    private val stageBossEmoji = listOf("🐺", "🦂", "🐍", "🦇", "🦍", "🦈", "🧌", "🐉", "👹", "👑")
    private val eliteBossEmoji = listOf("👹", "🐲", "🦖", "☠️", "👑")
    private val regionNames = listOf("Hutan Asterfall", "Rawa Terkutuk", "Pantai Terlupakan", "Pegunungan Beku", "Gurun Kehancuran")
    private val regionMobEmoji = listOf(listOf("🐺","🦊","🐗","🦌"), listOf("🐸","🐍","🦂","🐊"), listOf("🦀","🐙","🦈","🐚"), listOf("🐻","🦅","🐐","🧊"), listOf("🦂","🐪","🦎","🔥"))
    private var screen = "MAIN"
    private var equipmentReturnScreen = "MAIN"
    private var cityMessage = "Kota Asterfall: bicara dengan warga sebelum masuk hutan."
    private var playerName = prefs.getString("player_name", "").orEmpty()
    private var hero = prefs.getString("hero", "Vanguard").orEmpty()
    private var potions = prefs.getInt("potions", 2).coerceAtLeast(0)
    private var materials = prefs.getInt("materials", 0).coerceAtLeast(0)
    private var weaponLevel = prefs.getInt("weapon_level", 0).coerceIn(0, 5)
    private var armorLevel = prefs.getInt("armor_level", 0).coerceIn(0, 5)
    private var skillLevel = prefs.getInt("skill_level", 1).coerceIn(1, 5)

    init {
        restoreSavedStats()
        loadGameAssets()
        repeat(4) { spawn(if (it == 2) 1 else 0) }
    }

    private fun restoreSavedStats() {
        val baseHp = when (hero) {
            "Ranger" -> 110f
            "Mage" -> 90f
            else -> 140f
        }
        maxHp = prefs.getFloat("max_hp", baseHp).coerceIn(baseHp, 10000f)
        hp = prefs.getFloat("hp", maxHp).coerceIn(1f, maxHp)
        gemmaMaxHp = prefs.getFloat("gemma_max_hp", 100f).coerceIn(100f, 10000f)
        gemmaHp = prefs.getFloat("gemma_hp", gemmaMaxHp).coerceIn(1f, gemmaMaxHp)
        damage = prefs.getFloat("damage", (if (hero == "Ranger") 18f else 14f) + (level - 1) * 2f).coerceIn(1f, 100000f)
    }

    private fun loadGameAssets() {
        try { context.assets.open("graphics/urban_tiles.png").use { urbanSheet = BitmapFactory.decodeStream(it) } } catch (_: Exception) {}
        try { context.assets.open("graphics/rpg_tiles.png").use { rpgSheet = BitmapFactory.decodeStream(it) } } catch (_: Exception) {}
        try { context.assets.open("graphics/hero_samurai.png").use { heroSheet = BitmapFactory.decodeStream(it) } } catch (_: Exception) {}
        try { context.assets.open("graphics/gemma_ninja.png").use { gemmaSheet = BitmapFactory.decodeStream(it) } } catch (_: Exception) {}
        try { context.assets.open("graphics/pig.png").use { pigSheet = BitmapFactory.decodeStream(it) } } catch (_: Exception) {}
        refreshAreaBackdrop(force = true)
        listOf("step", "attack", "impact", "spell").forEach { name ->
            try { context.assets.openFd("audio/$name.ogg").use { afd -> soundIds[name] = soundPool.load(afd, 1) } } catch (_: Exception) {}
        }
        listOf(
            "laserSmall_000", "laserSmall_001", "laserSmall_002", "laserSmall_003", "laserSmall_004",
            "laserRetro_000", "laserRetro_001", "laserRetro_002", "laserRetro_003", "laserRetro_004",
            "laserLarge_000", "laserLarge_001", "laserLarge_002",
            "explosionCrunch_000", "explosionCrunch_001", "explosionCrunch_002",
            "impactGeneric_light_000", "impactGeneric_light_001", "impactGeneric_light_002",
            "impactMetal_000", "impactMetal_001",
            "footstep00", "footstep01", "footstep02", "footstep03", "footstep04"
        ).forEach { name ->
            try { context.assets.openFd("audio/extra/$name.ogg").use { afd -> soundIds[name] = soundPool.load(afd, 1) } } catch (_: Exception) {}
        }
    }

    private fun refreshAreaBackdrop(force: Boolean = false) {
        val next = listOf(0, 1, 2, 4, 5)[((campaignStage - 1) / 10).coerceIn(0, 4)].coerceAtMost(areaKeys.lastIndex)
        if (!force && next == activeAreaIndex && areaBackdrop != null) return
        activeAreaIndex = next
        try {
            val directory = "areas/${areaKeys[activeAreaIndex]}"
            val candidates = context.assets.list(directory).orEmpty()
                .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) || it.endsWith(".webp", true) }
                .sorted()
            val file = candidates.firstOrNull()
            if (file != null) {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                context.assets.open("$directory/$file").use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream, null, options)
                    areaBackdrop?.recycle()
                    areaBackdrop = decoded
                }
            }
        } catch (_: Exception) {}
    }

    private fun tickFootsteps(dt: Float) {
        if (moveX * moveX + moveY * moveY > 0.2f) {
            footstepTimer -= dt
            if (footstepTimer <= 0f) { sfx("step", ToneGenerator.TONE_PROP_BEEP); footstepTimer = 0.42f }
        } else footstepTimer = 0f
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }
    private fun need(l: Int): Int = (100 + l * l * 18).coerceAtMost(65000)
    private fun say(s: String, t: Float = 2f) { message = s; messageTime = t }
    private fun gemmaTalk(s: String, t: Float = 3.2f) { pendingGemmaChat = s }
    private fun gemmaRandomTalk(): String { val lines = listOf("Gaskeun! Kita kompak! 💖", "Hehe, monster itu pede banget! 😂", "Jangan lupa dodge, kapten! ✨", "Aku jagain dari sini! 🌟", "Waduh, mukanya serem... dikit! 😳", "Serang terus! Kamu hebat! 💪", "Tim kita nggak gampang tumbang! 💜", "Monster: kok aku lagi? WKWK! 🤣", "Fokus, fokus... habis ini istirahat! 😆", "Aku kasih dukungan magis! ✨"); return lines.random(random) }
    private fun performPlayerAttack() {
        if (attackTimer > 0f) { say("Serangan masih cooldown sebentar!", 0.7f); return }
        val target = mobs.filter { it.alive && dist(it.x, it.y, x, y) < 125f }.minByOrNull { dist(it.x, it.y, x, y) }
        if (target == null) { say("Dekati monster dulu untuk menyerang.", 1f); return }
        target.hp -= damage + weaponLevel * 5f; target.hitEmoji = "💥"; target.hitEmojiTime = 0.75f
        attackTimer = 0.48f; fx(x, y, target.x, target.y, 2); fx(target.x, target.y, target.x, target.y, 5, 0.28f); sfx("attack", ToneGenerator.TONE_PROP_BEEP)
        gemmaTalk(listOf("BOOM! Kena telak! 😂", "Nah gitu dong, jagoanku! 💖", "Wih, serangannya mantap! ✨").random(random))
        if (target.hp <= 0f) defeat(target) else say("Serangan player mengenai target!", 0.7f)
    }
    private fun sfx(asset: String, fallback: Int = ToneGenerator.TONE_PROP_BEEP) {
        try {
            val candidates = when (asset) {
                "step" -> listOf("footstep00", "footstep01", "footstep02", "footstep03", "footstep04", "step")
                "attack" -> listOf("laserSmall_000", "laserSmall_001", "laserSmall_002", "laserSmall_003", "laserSmall_004", "attack")
                "spell" -> listOf("laserRetro_000", "laserRetro_001", "laserRetro_002", "laserLarge_000", "laserLarge_001", "spell")
                "impact" -> listOf("impactGeneric_light_000", "impactGeneric_light_001", "impactGeneric_light_002", "impactMetal_000", "impactMetal_001", "impact")
                "coins" -> listOf("impactGeneric_light_002", "impactGeneric_light_000", "impact")
                "boss" -> listOf("explosionCrunch_000", "explosionCrunch_001", "explosionCrunch_002", "spell")
                "door", "book", "ui" -> listOf("spell", "laserRetro_001")
                else -> listOf(asset)
            }
            val available = candidates.mapNotNull { soundIds[it] }
            val id = if (available.isNotEmpty()) available[random.nextInt(available.size)] else null
            val rate = when (asset) {
                "step" -> 0.92f + random.nextFloat() * 0.18f
                "attack", "spell" -> 0.90f + random.nextFloat() * 0.22f
                else -> 0.94f + random.nextFloat() * 0.14f
            }
            val volume = if (asset == "step") 0.48f else 0.78f + random.nextFloat() * 0.16f
            val stream = if (id != null) soundPool.play(id, volume, volume, 1, 0, rate) else 0
            if (stream == 0) tone.startTone(fallback, if (fallback == ToneGenerator.TONE_PROP_BEEP) 150 else 210)
        } catch (_: Exception) { try { tone.startTone(fallback, 150) } catch (_: Exception) {} }
    }
    private fun fx(sx: Float, sy: Float, tx: Float, ty: Float, kind: Int, life: Float = 0.32f) { effects.add(Effect(sx,sy,tx,ty,life,kind)) }
    private fun save() {
        prefs.edit()
            .putInt("save_version", 2)
            .putInt("level", level).putInt("exp", exp).putInt("gold", gold).putInt("kills", kills)
            .putInt("campaign_stage", campaignStage).putInt("campaign_unlocked_stage", unlockedStage).putInt("campaign_stage_kills", stageKills)
            .putInt("gemma_level", gemmaLevel).putInt("gemma_exp", gemmaExp)
            .putString("player_name", playerName).putString("hero", hero)
            .putInt("potions", potions).putInt("materials", materials)
            .putInt("weapon_level", weaponLevel).putInt("armor_level", armorLevel).putInt("skill_level", skillLevel)
            .putFloat("hp", hp).putFloat("max_hp", maxHp)
            .putFloat("gemma_hp", gemmaHp).putFloat("gemma_max_hp", gemmaMaxHp).putFloat("damage", damage)
            .apply()
    }

    private fun exportSaveText(): String = JSONObject()
        .put("save_version", 2)
        .put("game", "STARFALL_RPG")
        .put("level", level).put("exp", exp).put("gold", gold).put("kills", kills)
        .put("campaign_stage", campaignStage).put("campaign_unlocked_stage", unlockedStage).put("campaign_stage_kills", stageKills)
        .put("gemma_level", gemmaLevel).put("gemma_exp", gemmaExp)
        .put("player_name", playerName).put("hero", hero)
        .put("potions", potions).put("materials", materials)
        .put("weapon_level", weaponLevel).put("armor_level", armorLevel).put("skill_level", skillLevel)
        .put("hp", hp.toDouble()).put("max_hp", maxHp.toDouble())
        .put("gemma_hp", gemmaHp.toDouble()).put("gemma_max_hp", gemmaMaxHp.toDouble())
        .put("damage", damage.toDouble()).toString(2)

    private fun showExportDialog() {
        val saveText = exportSaveText()
        val field = EditText(context).apply {
            setText(saveText)
            setSingleLine(false)
            minLines = 7
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(context)
            .setTitle("Ekspor Save Starfall")
            .setMessage("Cadangan JSON versi 2. Memuat progres Campaign dan EXP Gemma. Simpan teks ini untuk pemulihan setelah memasang ulang game.")
            .setView(field)
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Salin Save") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Starfall RPG Save", saveText))
                say("Save berhasil disalin ke clipboard.", 3f)
                invalidate()
            }
            .show()
    }

    private fun showImportDialog() {
        val field = EditText(context).apply {
            hint = "Tempel JSON save Starfall di sini"
            setSingleLine(false)
            minLines = 7
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(context)
            .setTitle("Impor Save Starfall")
            .setMessage("Tempel cadangan JSON versi 1 atau 2. Save lama akan dimigrasikan.")
            .setView(field)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Impor") { _, _ -> importSaveText(field.text.toString()) }
            .show()
    }

    private fun importSaveText(raw: String) {
        try {
            val data = JSONObject(raw)
            if (data.optString("game", "STARFALL_RPG") != "STARFALL_RPG") {
                say("File ini bukan save Starfall RPG.", 3f); invalidate(); return
            }
            val version = data.optInt("save_version", 1)
            if (version !in 1..2) {
                say("Versi save tidak didukung: $version.", 3f); invalidate(); return
            }
            level = data.optInt("level", level).coerceIn(1, 100)
            campaignStage = data.optInt("campaign_stage", campaignStage).coerceIn(1, 50)
            unlockedStage = data.optInt("campaign_unlocked_stage", unlockedStage).coerceIn(1, 50).coerceAtLeast(campaignStage)
            stageKills = data.optInt("campaign_stage_kills", stageKills).coerceIn(0, 30)
            gemmaLevel = data.optInt("gemma_level", gemmaLevel).coerceIn(1, 100)
            gemmaExp = data.optInt("gemma_exp", gemmaExp).coerceAtLeast(0)
            exp = data.optInt("exp", exp).coerceAtLeast(0)
            gold = data.optInt("gold", gold).coerceAtLeast(0)
            kills = data.optInt("kills", kills).coerceAtLeast(0)
            playerName = data.optString("player_name", playerName).take(18)
            hero = when (data.optString("hero", hero)) {
                "Vanguard", "Ranger", "Mage" -> data.optString("hero", hero)
                else -> "Vanguard"
            }
            potions = data.optInt("potions", potions).coerceAtLeast(0)
            materials = data.optInt("materials", materials).coerceAtLeast(0)
            weaponLevel = data.optInt("weapon_level", weaponLevel).coerceIn(0, 5)
            armorLevel = data.optInt("armor_level", armorLevel).coerceIn(0, 5)
            skillLevel = data.optInt("skill_level", skillLevel).coerceIn(1, 5)
            val baseHp = when (hero) { "Ranger" -> 110f; "Mage" -> 90f; else -> 140f }
            maxHp = data.optDouble("max_hp", baseHp.toDouble()).toFloat().coerceIn(baseHp, 10000f)
            hp = data.optDouble("hp", maxHp.toDouble()).toFloat().coerceIn(1f, maxHp)
            gemmaMaxHp = data.optDouble("gemma_max_hp", 100.0).toFloat().coerceIn(100f, 10000f)
            gemmaHp = data.optDouble("gemma_hp", gemmaMaxHp.toDouble()).toFloat().coerceIn(1f, gemmaMaxHp)
            damage = data.optDouble("damage", ((if (hero == "Ranger") 18f else 14f) + (level - 1) * 2f).toDouble()).toFloat().coerceIn(1f, 100000f)
            save()
            screen = "MAIN"
            moveX = 0f; moveY = 0f
            say("Save berhasil diimpor. Selamat kembali, ${playerName.ifBlank { "Adventurer" }}!", 4f)
        } catch (_: Exception) {
            say("Save tidak valid. Periksa teks JSON lalu coba lagi.", 3f)
        }
        invalidate()
    }
    private fun blocked(ax: Float, ay: Float) = cityBlocked(ax, ay)

    private fun spawn(kind: Int) {
        var sx = 38f; var sy = 350f; var tries = 0
        do {
            sx = if (random.nextBoolean()) 38f else 922f
            sy = 350f + random.nextFloat() * 85f
            tries++
        } while (tries < 18 && (dist(sx, sy, x, y) < 190f || cityBlocked(sx, sy)))
        val max = if (kind == 1) 85f else if (kind == 2) 1200f else 50f
        val mob = Mob(sx, sy, kind, max, max, 1.2f + random.nextFloat())
        val mobSet = regionMobEmoji[((campaignStage - 1) / 10).coerceIn(0, regionMobEmoji.lastIndex)]
        mob.displayEmoji = if (kind == 2) "👹" else mobSet.random(random)
        mobs.add(mob)
    }

    private fun update(dt: Float) {
        clock += dt
        screenShake = (screenShake - dt).coerceAtLeast(0f)
        tickFootsteps(dt)
        hitFlash = max(0f, hitFlash-dt)
        effects.forEach { it.life -= dt }
        effects.removeAll { it.life <= 0f }
        mobs.forEach { it.hitEmojiTime = max(0f, it.hitEmojiTime - dt); if (it.hitEmojiTime <= 0f) it.hitEmoji = "" }
        messageTime = max(0f, messageTime - dt)
        gemmaChatTime = max(0f, gemmaChatTime - dt); gemmaChatTimer -= dt
        if (gemmaChatTimer <= 0f) {
            gemmaChat = pendingGemmaChat ?: gemmaRandomTalk()
            pendingGemmaChat = null
            gemmaChatTime = 3.4f
            gemmaChatTimer = 5f
        }
        attackTimer = max(0f, attackTimer - dt); gemmaTimer = max(0f, gemmaTimer - dt)
        dodgeCooldown = max(0f, dodgeCooldown - dt); dodgeTime = max(0f, dodgeTime - dt)
        skillCooldown = max(0f, skillCooldown - dt); invuln = max(0f, invuln - dt)
        mana = min(maxMana, mana + dt * 4f)

        val speed = if (dodgeTime > 0f) 340f else 170f
        val nx = x + moveX * speed * dt; val ny = y + moveY * speed * dt
        if (!blocked(nx, y)) x = nx
        if (!blocked(x, ny)) y = ny
        val followX = if (command == "RANGE") x - 90f else x - 42f
        val followY = y + 34f
        gemmaX += (followX.coerceIn(35f, 925f) - gemmaX) * min(1f, dt * 2.6f)
        gemmaY += (followY.coerceIn(85f, 435f) - gemmaY) * min(1f, dt * 2.6f)
        if (dist(gemmaX, gemmaY, x, y) > 180f) { gemmaX = x - 45f; gemmaY = y + 30f }

        mobs.forEach { m ->
            if (!m.alive) return@forEach
            val wasWinding = m.windup
            m.attack = max(0f, m.attack - dt)
            if (m.windup > 0f) m.windup = max(0f, m.windup - dt)
            val d = dist(m.x, m.y, x, y)
            if (d > 36f) {
                val mobSpeed = if (m.kind == 1) 74f else if (m.kind == 2) 42f else 49f
                val vx = (x - m.x) / max(1f, d); val vy = (y - m.y) / max(1f, d)
                val ex = m.x + vx * mobSpeed * dt; val ey = m.y + vy * mobSpeed * dt
                if (!blocked(ex, m.y)) m.x = ex
                if (!blocked(m.x, ey)) m.y = ey
            } else if (m.attack <= 0f && m.windup <= 0f) {
                m.windup = if (m.kind == 2) 1f else if (m.kind == 1) 0.7f else 0.55f
                say(if (m.kind == 2) "Guardian mengangkat akar!" else if (m.kind == 1) "Serigala bersiap menerjang!" else "Slime menggembung!", 1f)
            }
            if (wasWinding > 0f && m.windup <= 0f) {
                val radius = if (m.kind == 2) 88f else 58f
                if (dist(m.x, m.y, x, y) < radius && invuln <= 0f) {
                    hp -= max(1f, (if (m.kind == 2) 20f else if (m.kind == 1) 12f else 8f) - armorLevel*3f)
                    hitFlash=0.22f; fx(x,y,m.x,m.y,3); fx(x,y,x,y,6,0.22f); sfx("impact", ToneGenerator.TONE_PROP_NACK)
                    say("Kena serangan! Baca tanda lalu dodge.", 1.7f)
                }
                m.attack = if (m.kind == 2) 4f else if (m.kind == 1) 3f else 2.5f
            }
        }

        if (gemmaTimer <= 0f && command != "GUARD") {
            val target = mobs.filter { it.alive && dist(it.x, it.y, gemmaX, gemmaY) < 230f }
                .minByOrNull { dist(it.x, it.y, gemmaX, gemmaY) }
            if (target != null) {
                target.hp -= 10f + (level - 1) * 2f; gemmaTimer = 2f
                target.hitEmoji = "🌟"; target.hitEmojiTime = 0.7f
                fx(gemmaX,gemmaY,target.x,target.y,1,0.52f); sfx("spell", ToneGenerator.TONE_PROP_ACK)
                gemmaTalk(listOf("Arcane Bolt! ✨", "Kena sihirku! Hehe! 🌟", "Dari Gemma dengan cinta! 💜").random(random), 2.5f)
                if (target.hp <= 0f) defeat(target)
            }
        }
        if (hp <= 0f || gemmaHp <= 0f) {
            x = 470f; y = 370f; gemmaX = 425f; gemmaY = 395f; hp = maxHp; gemmaHp = gemmaMaxHp
            mobs.filter { it.alive && it.kind != 2 }.forEach { it.x = (it.x + 180f).coerceIn(55f, 900f) }
            say("Mundur ke tempat aman. Gold dan EXP tetap utuh.", 4f)
        }
        if (!bossSpawned && stageKills < 30 && mobs.count { it.alive && it.kind != 2 } < 4 && random.nextFloat() < dt * 0.35f)
            spawn(if (random.nextFloat() < 0.28f) 1 else 0)
        if (!bossSpawned && stageKills >= 30) {
            bossSpawned = true
            val elite = campaignStage % 10 == 0
            val bossHp = if (elite) 5200f + campaignStage * 420f else 950f + campaignStage * 135f
            val bossEmoji = if (elite) eliteBossEmoji[(campaignStage / 10 - 1).coerceIn(0, 4)] else stageBossEmoji[(campaignStage - 1) % stageBossEmoji.size]
            mobs.add(Mob(810f, 365f, 2, bossHp, bossHp, if (elite) 3.8f else 2.4f, displayEmoji = bossEmoji))
            val bossName = if (elite) "ELITE BOSS " + eliteBossEmoji[(campaignStage / 10 - 1).coerceIn(0, 4)] else "BOS " + stageBossEmoji[(campaignStage - 1) % stageBossEmoji.size]
            say("$bossName · STAGE $campaignStage MUNCUL!", 5f)
            gemmaTalk(if (elite) "ELITE BOSS?! Ini bukan latihan lagi! Kita pasti bisa! 😤💖" else "Bosnya datang! Dodge yang tepat, ya! ⚔️", 4f)
        }
    }

    private fun defeat(m: Mob) {
        if (!m.alive) return
        m.alive = false
        fx(m.x,m.y,m.x,m.y,4,0.55f); sfx(if (m.kind == 2) "boss" else "coins", ToneGenerator.TONE_PROP_ACK)
        if (m.kind == 2) {
            bossCleared = true
            val elite = campaignStage % 10 == 0
            val rewardGold = if (elite) 1200 + campaignStage * 30 else 120 + campaignStage * 12
            val rewardExp = if (elite) 1800 + campaignStage * 45 else 280 + campaignStage * 12
            gold += rewardGold; exp += rewardExp; gemmaExp += rewardExp / 2
            if (campaignStage < 50) unlockedStage = max(unlockedStage, campaignStage + 1)
            if (elite) { materials += 12; potions += 5; say("ELITE BOSS TUMBANG! +$rewardGold GOLD · +$rewardExp EXP · WILAYAH BARU!", 5f) }
            else { materials += 3; say("BOS STAGE $campaignStage KALAH! Stage berikutnya terbuka. +$rewardGold GOLD", 4f) }
            levelUp(); levelUpGemma(); save(); return
        }
        kills++
        stageKills = (stageKills + 1).coerceAtMost(30)
        val oldAreaIndex = activeAreaIndex
        refreshAreaBackdrop()
        if (activeAreaIndex != oldAreaIndex) say("Wilayah baru terbuka: "+areaNames[activeAreaIndex]+"!", 3f)
        if (m.kind == 1) { exp += 22; gold += random.nextInt(6, 11); gemmaExp += 11 }
        else { exp += 12; gold += random.nextInt(3, 7); gemmaExp += 6 }
        val roll = random.nextInt(100)
        val drop = if (m.kind == 1 && roll < 60) { materials++; "material umum" }
            else if (m.kind == 0 && roll < 30) { materials++; "material umum" }
            else if (roll < 38) { potions++; "ramuan HP" } else ""
        say(if (drop.isEmpty()) "Monster tumbang! EXP dan gold didapat." else "Drop: $drop", 2f)
        levelUp(); levelUpGemma(); save()
    }
    private fun levelUp() {
        while (level < 100 && exp >= need(level)) {
            exp -= need(level); level++; maxHp += 10f; damage += 2f
            hp = maxHp
            say("LEVEL UP! Level $level. Perjuanganmu membuahkan hasil!", 3f)
        }
    }
    private fun gemmaNeed(l: Int): Int = (80 + l * l * 14).coerceAtMost(65000)
    private fun levelUpGemma() {
        while (gemmaLevel < 100 && gemmaExp >= gemmaNeed(gemmaLevel)) {
            gemmaExp -= gemmaNeed(gemmaLevel); gemmaLevel++; gemmaMaxHp += 8f
            gemmaHp = gemmaMaxHp
            say("GEMMA LEVEL UP! Lv.$gemmaLevel 💖", 2.5f)
        }
    }
    private fun startCampaignStage(stage: Int) {
        if (stage !in 1..unlockedStage) { say("Kalahkan bos stage sebelumnya dulu!", 2f); return }
        campaignStage = stage.coerceIn(1, 50)
        stageKills = 0; bossSpawned = false; bossCleared = false
        mobs.clear()
        repeat(4) { spawn(if (it == 2) 1 else 0) }
        refreshAreaBackdrop(force = true)
        screen = "GAME"; x = 470f; y = 350f; gemmaX = 425f; gemmaY = 380f
        moveX = 0f; moveY = 0f
        say("STAGE $campaignStage · " + regionNames[(campaignStage - 1) / 10] + " · Kalahkan 30 monster lalu bos!", 4f)
        save()
    }
    private fun drawSprite(c: Canvas, sheet: Bitmap?, sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float) {
        if (sheet == null || sx < 0 || sy < 0 || sx + sw > sheet.width || sy + sh > sheet.height) return
        paint.shader = null; paint.alpha = 255; paint.isFilterBitmap = true
        c.drawBitmap(sheet, android.graphics.Rect(sx, sy, sx + sw, sy + sh), RectF(dx, dy, dx + dw, dy + dh), paint)
        paint.isFilterBitmap = false
    }

    // Supports both common 4x4 direction-row sheets and 4x7 animation-row sheets.
    // Derive source frame dimensions from the real bitmap: never crop a 16px corner
    // out of a 32px character, which was the reason the city sprites looked missing.
    private fun facingColumn(dx: Float, dy: Float): Int {
        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) return if (dx >= 0f) 3 else 2
        return if (dy >= 0f) 0 else 1
    }

    private fun drawAnimatedCharacter(c: Canvas, sheet: Bitmap?, cx: Float, feetY: Float,
                                      moving: Boolean, direction: Int, widthPx: Float, heightPx: Float) {
        if (sheet == null || sheet.width < 4 || sheet.height < 4) return
        val frameW = sheet.width / 4
        val rows = (sheet.height / frameW).coerceAtLeast(1)
        val frameH = if (rows >= 7) sheet.height / 7 else sheet.height / 4
        if (frameW <= 0 || frameH <= 0) return
        val d = direction.coerceIn(0, 3)
        val frame = if (moving) 1 + ((clock * 7f).toInt() % 3) else 0
        val src = if (rows >= 7) {
            android.graphics.Rect(d * frameW, (if (moving) frame else 0) * frameH,
                (d + 1) * frameW, ((if (moving) frame else 0) + 1) * frameH)
        } else {
            android.graphics.Rect(frame * frameW, d * frameH,
                (frame + 1) * frameW, (d + 1) * frameH)
        }
        if (src.right > sheet.width || src.bottom > sheet.height) return
        val breathe = if (moving) 1f else 1f + kotlin.math.sin(clock * 3.2f) * 0.018f
        val dstH = heightPx * breathe
        val dst = RectF(cx - widthPx / 2f, feetY - dstH, cx + widthPx / 2f, feetY)
        paint.shader = null; paint.alpha = 255; paint.isFilterBitmap = false
        c.drawBitmap(sheet, src, dst, paint)
        paint.isFilterBitmap = false; paint.alpha = 255
    }

    private fun text(c: Canvas, s: String, tx: Float, ty: Float, size: Float, color: Int = Color.WHITE) {
        paint.color = color; paint.textSize = size; paint.setShadowLayer(2f, 1f, 1f, Color.BLACK)
        c.drawText(s, tx, ty, paint); paint.clearShadowLayer()
    }
    private fun bar(c: Canvas, bx: Float, by: Float, w: Float, h: Float, v: Float, maxV: Float, color: Int) {
        paint.color = Color.rgb(24, 30, 28); c.drawRect(bx, by, bx + w, by + h, paint)
        paint.color = color; c.drawRect(bx + 2, by + 2, bx + 2 + (w - 4) * (v / maxV).coerceIn(0f, 1f), by + h - 2, paint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 960f, height / 540f)
        if (screen == "GAME") {
            update(0.016f)
            if (screenShake > 0f) canvas.translate((kotlin.math.sin(clock*95f)*screenShake*20f), (kotlin.math.cos(clock*81f)*screenShake*10f))
            drawBattleArena(canvas); drawCharacters(canvas); drawEffects(canvas); drawHud(canvas)
        } else if (screen == "CITY") {
            updateCity(0.016f)
            drawCity(canvas)
            drawCityCharacters(canvas)
            drawCityHud(canvas)
        } else drawMenu(canvas)
        canvas.restore(); postInvalidateDelayed(16L)
    }
    private fun drawForest(c: Canvas) {
        c.drawColor(Color.rgb(24, 45, 36))
        paint.shader=LinearGradient(0f,0f,960f,540f,Color.rgb(28,65,48),Color.rgb(11,31,34),Shader.TileMode.CLAMP); c.drawRect(0f,0f,960f,540f,paint); paint.shader=null
        for (tx in 0..59) for (ty in 0..32) {
            val xx = tx * 16f; val yy = ty * 16f
            val tone = (tx * 17 + ty * 31 + tx * ty * 3) % 9
            paint.color = when (tone) {
                0 -> Color.rgb(31, 66, 43)
                1, 2 -> Color.rgb(28, 59, 40)
                3 -> Color.rgb(32, 63, 42)
                else -> Color.rgb(29, 61, 42)
            }
            c.drawRect(xx, yy, xx + 16f, yy + 16f, paint)
            // Sparse 2-3px grass flecks and tiny soil pixels, not a repeated checker pattern.
            if ((tx * 3 + ty * 11) % 19 == 0) { paint.color = Color.rgb(62, 100, 57); c.drawRect(xx+4,yy+5,xx+7,yy+7,paint) }
            if ((tx * 13 + ty * 5) % 37 == 0) { paint.color = Color.rgb(48, 78, 48); c.drawRect(xx+10,yy+11,xx+13,yy+13,paint) }
        }
        // Use each uploaded biome backdrop without distorting portrait images.
        areaBackdrop?.let { bg ->
            val targetRatio = 960f / 540f
            val sourceRatio = bg.width.toFloat() / bg.height.toFloat()
            val sw: Int
            val sh: Int
            val sx: Int
            val sy: Int
            if (sourceRatio > targetRatio) {
                sh = bg.height; sw = (bg.height * targetRatio).toInt().coerceAtMost(bg.width)
                sx = (bg.width - sw) / 2; sy = 0
            } else {
                sw = bg.width; sh = (bg.width / targetRatio).toInt().coerceAtMost(bg.height)
                sx = 0; sy = (bg.height - sh) / 2
            }
            paint.shader = null; paint.alpha = 205; paint.isFilterBitmap = true
            c.drawBitmap(bg, android.graphics.Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, 960f, 540f), paint)
            paint.alpha = 255; paint.isFilterBitmap = false
            paint.color = Color.argb(86, 7, 16, 25); c.drawRect(0f, 0f, 960f, 540f, paint)
        }
        if (rpgSheet != null) {
            for (gx in 0..14) for (gy in 0..7) {
                if ((gx * 5 + gy * 3) % 4 == 0) drawSprite(c, rpgSheet, 0, 150, 16, 16, gx * 64f, gy * 64f, 64f, 64f)
            }
        }
        if (activeAreaIndex == 0 || activeAreaIndex == 1) trees.forEach { t ->
            paint.color = Color.argb(90,3,15,12); c.drawOval(RectF(t.left-9,t.bottom-3,t.right+12,t.bottom+15),paint)
            paint.color = Color.rgb(79,57,37); c.drawRoundRect(RectF(t.centerX()-8,t.centerY()-3,t.centerX()+9,t.bottom+10),4f,4f,paint)
            paint.shader=LinearGradient(t.left,t.top,t.right,t.bottom,Color.rgb(72,142,75),Color.rgb(18,63,43),Shader.TileMode.CLAMP); c.drawOval(RectF(t.left-7,t.top-7,t.right+7,t.bottom-3),paint); paint.shader=null
            paint.color = Color.rgb(56,126,61); c.drawOval(RectF(t.left+3,t.top+2,t.right-5,t.bottom-11),paint)
            paint.color = Color.rgb(103,170,80); c.drawCircle(t.left+16,t.top+15,5f,paint); c.drawCircle(t.left+29,t.top+24,4f,paint)
            if (urbanSheet != null) drawSprite(c, urbanSheet, 512, 134, 16, 32, t.left-10, t.top-15, 65f, 80f)
        }
        when (activeAreaIndex) {
            1 -> repeat(18) { i -> val px = ((i * 137 + clock.toInt() * 3) % 940).toFloat() + 10f; val py = ((i * 71 + clock.toInt() * 5) % 500).toFloat() + 20f
                paint.color = Color.argb(100, 178, 218, 106); c.drawCircle(px, py, 2.2f, paint) }
            2 -> { paint.color = Color.argb(90, 174, 226, 239); repeat(8) { i -> val yy = 85f + i * 54f; c.drawLine(0f, yy + kotlin.math.sin(clock * 1.5f + i) * 5f, 960f, yy, paint) } }
            3 -> { paint.color = Color.argb(120, 216, 220, 215); repeat(14) { i -> val px = ((i * 113 + 17) % 940).toFloat() + 10f; val py = ((i * 67 + 23) % 500).toFloat() + 20f; c.drawRect(px, py, px + 3f, py + 3f, paint) } }
            4 -> { paint.color = Color.argb(180, 240, 248, 255); repeat(28) { i -> val px = ((i * 83 + (clock * (10 + i % 7)).toInt()) % 960).toFloat(); val py = ((i * 61 + (clock * (16 + i % 5)).toInt()) % 540).toFloat(); c.drawCircle(px, py, 1.5f + (i % 3), paint) } }
            5 -> { paint.color = Color.argb(95, 255, 222, 148); repeat(18) { i -> val px = ((i * 97 + 31) % 940).toFloat() + 10f; val py = ((i * 59 + 13) % 500).toFloat() + 20f; c.drawRect(px, py, px + 3f, py + 2f, paint) } }
            6 -> repeat(18) { i -> val px = ((i * 127 + (clock * (8 + i % 6)).toInt()) % 960).toFloat(); val py = 60f + ((i * 71 + (clock * (10 + i % 4)).toInt()) % 470).toFloat()
                paint.color = Color.argb(165, 255, if (i % 2 == 0) 104 else 57, 38); c.drawCircle(px, py, 1.8f + (i % 2), paint) }
        }
        paint.color = Color.rgb(95,78,52); c.drawRect(430f,255f,520f,315f,paint)
        paint.color = Color.rgb(120,101,66); c.drawRect(442f,264f,508f,305f,paint)
    }
    private fun drawEmoji(c: Canvas, emoji: String, cx: Float, cy: Float, size: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textSize = size
        paint.setShadowLayer(5f, 0f, 3f, Color.BLACK)
        paint.color = Color.WHITE
        c.drawText(emoji, cx, cy + size * 0.34f, paint)
        paint.clearShadowLayer()
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }

    private fun drawCharacters(c: Canvas) {
        mobs.filter { it.alive }.forEach { m ->
            if (m.kind == 2) {
                val elite = campaignStage % 10 == 0
                paint.color = Color.argb(125,0,0,0); c.drawOval(RectF(m.x-68,m.y+27,m.x+68,m.y+48),paint)
                bar(c,m.x-55,m.y-78,110f,9f,m.hp,m.maxHp,Color.rgb(210,64,64))
                text(c,if (elite) "ELITE STAGE $campaignStage" else "BOSS STAGE $campaignStage",m.x-65,m.y-88,12f,Color.rgb(255,220,140))
                drawEmoji(c, m.displayEmoji.ifBlank { if (elite) eliteBossEmoji[(campaignStage / 10 - 1).coerceIn(0,4)] else stageBossEmoji[(campaignStage - 1) % stageBossEmoji.size] }, m.x, m.y-6f, 104f)
                if (m.hitEmojiTime > 0f && m.hitEmoji.isNotBlank()) drawEmoji(c,m.hitEmoji,m.x,m.y-6f,116f)
                if (m.windup > 0f) text(c,"DODGE!",m.x-24,m.y+58,14f,Color.YELLOW)
            } else {
                paint.color = Color.argb(110,0,0,0); c.drawOval(RectF(m.x-37,m.y+16,m.x+37,m.y+29),paint)
                bar(c,m.x-31,m.y-51,62f,7f,m.hp,m.maxHp,Color.rgb(207,70,65))
                if (m.hitEmojiTime > 0f && m.hitEmoji.isNotBlank()) {
                    // Full-cover hit/skill emote: intentionally hides the base monster completely.
                    drawEmoji(c, m.hitEmoji, m.x, m.y-8f, 76f)
                } else {
                    // One stable, oversized regional monster emote replaces the sprite underneath.
                    drawEmoji(c, m.displayEmoji.ifBlank { "👾" }, m.x, m.y-8f, 66f)
                }
                if (m.windup > 0f) text(c,"!",m.x-3,m.y-53,20f,Color.YELLOW)
            }
        }
        paint.color = Color.argb(80,0,0,0); c.drawOval(RectF(gemmaX-18,gemmaY+4,gemmaX+19,gemmaY+15),paint)
        val gemmaMoving = dist(gemmaX,gemmaY,x,y) > 54f
        drawAnimatedCharacter(c, gemmaSheet, gemmaX, gemmaY+5f, gemmaMoving, facingColumn(x-gemmaX,y-gemmaY), 43f, 48f)
        text(c,"GEMMA",gemmaX-23,gemmaY-49,9f,Color.rgb(235,202,255))
        text(c,"✦",gemmaX+13,gemmaY-35+kotlin.math.sin(clock*4f)*3f,12f,Color.rgb(168,224,255))
        if (gemmaChatTime > 0f) {
            val bubbleW = 278f; val bubbleH = 58f
            val bx = (gemmaX + 24f).coerceIn(8f, 674f)
            val by = (gemmaY - 112f).coerceIn(58f, 408f)
            paint.setShadowLayer(6f, 0f, 2f, Color.BLACK)
            paint.color = Color.rgb(255, 253, 244)
            c.drawRoundRect(RectF(bx, by, bx + bubbleW, by + bubbleH), 12f, 12f, paint)
            paint.clearShadowLayer()
            paint.color = Color.rgb(103, 66, 139); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
            c.drawRoundRect(RectF(bx, by, bx + bubbleW, by + bubbleH), 12f, 12f, paint); paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            paint.textSize = 15f; paint.textAlign = Paint.Align.LEFT; paint.color = Color.rgb(38, 29, 48)
            val words = gemmaChat.split(" ")
            var line1 = ""; var line2 = ""
            words.forEach { word ->
                val candidate = if (line1.isEmpty()) word else "$line1 $word"
                if (paint.measureText(candidate) <= bubbleW - 20f && line2.isEmpty()) line1 = candidate
                else line2 = if (line2.isEmpty()) word else "$line2 $word"
            }
            c.drawText(line1, bx + 10f, by + 23f, paint)
            c.drawText(line2, bx + 10f, by + 44f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        if (invuln <= 0f || (clock * 12f).toInt() % 2 == 0) {
            paint.color=Color.argb(80,0,0,0); c.drawOval(RectF(x-17,y+6,x+18,y+17),paint)
            val moving = kotlin.math.abs(moveX)+kotlin.math.abs(moveY) > 0.08f
            drawAnimatedCharacter(c, heroSheet, x, y+5f, moving, if (moving) facingColumn(moveX,moveY) else 0, 47f, 51f)
            if(hitFlash>0f){paint.color=Color.argb(100,255,60,60);c.drawCircle(x,y-3,23f,paint)}
        }
    }

    private fun drawEffects(c: Canvas) {
        effects.forEach { e ->
            val duration = if (e.kind == 1) 0.52f else if (e.kind == 4 || e.kind == 5 || e.kind == 7) 0.55f else 0.32f
            val p = (1f - e.life / duration).coerceIn(0f, 1f)
            val alpha = (230f * (1f - p)).toInt().coerceIn(0, 230)
            when (e.kind) {
                1 -> { // compact magic bolt
                    val px=e.x+(e.tx-e.x)*p; val py=e.y+(e.ty-e.y)*p
                    paint.color=Color.argb(alpha,117,174,255); c.drawRect(px-3f,py-3f,px+4f,py+4f,paint)
                    paint.color=Color.argb(alpha,225,242,255); c.drawRect(px-1f,py-7f,px+2f,py+7f,paint)
                }
                2 -> { // two crisp impact sparks
                    paint.color=Color.argb(alpha,255,226,132); paint.strokeWidth=2.5f
                    c.drawLine(e.x-8f,e.y-9f,e.x-2f,e.y-2f,paint); c.drawLine(e.x+8f,e.y-8f,e.x+2f,e.y-2f,paint)
                    c.drawLine(e.x-7f,e.y+7f,e.x-1f,e.y+1f,paint); c.drawLine(e.x+7f,e.y+7f,e.x+1f,e.y+1f,paint)
                    paint.strokeWidth=1f
                }
                3 -> { // short red hit ring
                    paint.color=Color.argb(alpha,232,91,77); paint.style=Paint.Style.STROKE; paint.strokeWidth=2f
                    c.drawRect(e.x-8f-8f*p,e.y-7f-8f*p,e.x+8f+8f*p,e.y+7f+8f*p,paint)
                    paint.style=Paint.Style.FILL; paint.strokeWidth=1f
                }
                4 -> { // small gold impact star, drawn as four lines
                    paint.color=Color.argb(alpha,255,218,111); paint.strokeWidth=2f
                    val r=5f+7f*p
                    c.drawLine(e.x-r,e.y,e.x+r,e.y,paint); c.drawLine(e.x,e.y-r,e.x,e.y+r,paint)
                    c.drawLine(e.x-r*.65f,e.y-r*.65f,e.x+r*.65f,e.y+r*.65f,paint)
                    c.drawLine(e.x-r*.65f,e.y+r*.65f,e.x+r*.65f,e.y-r*.65f,paint)
                    paint.strokeWidth=1f
                }
                5 -> {
                    paint.color=Color.argb(alpha,65,198,255); paint.strokeWidth=10f
                    c.drawLine(e.x-31f+16f*p,e.y+25f,e.x+29f-16f*p,e.y-30f,paint)
                    paint.color=Color.argb(alpha,240,251,255); paint.strokeWidth=3f
                    c.drawLine(e.x-27f+16f*p,e.y+21f,e.x+25f-16f*p,e.y-26f,paint)
                    paint.color=Color.argb(alpha,255,255,219); c.drawRect(e.x-4f,e.y-6f,e.x+4f,e.y+2f,paint); paint.strokeWidth=1f
                }
                6 -> {
                    paint.color=Color.argb((alpha*0.7f).toInt(),255,244,197); c.drawRect(e.x-12f,e.y-12f,e.x+12f,e.y+12f,paint)
                    paint.color=Color.argb(alpha,255,205,91); paint.strokeWidth=3f
                    c.drawLine(e.x-22f,e.y,e.x-12f,e.y,paint); c.drawLine(e.x+12f,e.y,e.x+22f,e.y,paint)
                    c.drawLine(e.x,e.y-22f,e.x,e.y-12f,paint); c.drawLine(e.x,e.y+12f,e.x,e.y+22f,paint); paint.strokeWidth=1f
                }
                7 -> {
                    paint.color=Color.argb(alpha,74,194,255); paint.strokeWidth=8f
                    val left=e.x-36f+12f*p; val right=e.x+36f-12f*p
                    c.drawLine(left,e.y+17f,right-10f,e.y-10f,paint); c.drawLine(right-10f,e.y-10f,right,e.y-25f,paint)
                    paint.color=Color.argb(alpha,245,252,255); paint.strokeWidth=2.5f
                    c.drawLine(left+3f,e.y+13f,right-11f,e.y-10f,paint); c.drawLine(right-11f,e.y-10f,right-1f,e.y-22f,paint); paint.strokeWidth=1f
                }
            }
        }
    }

    private fun drawPixelIcon(c: Canvas, kind: String, cx: Float, cy: Float, color: Int) {
        paint.color = color
        when (kind) {
            "attack" -> {
                paint.strokeWidth=3f
                c.drawLine(cx-7f,cy+7f,cx+6f,cy-6f,paint)
                c.drawLine(cx+1f,cy-7f,cx+8f,cy-8f,paint); c.drawLine(cx+6f,cy-1f,cx+8f,cy-8f,paint)
                c.drawLine(cx-9f,cy+9f,cx-4f,cy+8f,paint); paint.strokeWidth=1f
            }
            "dodge" -> {
                c.drawRect(cx-9f,cy-2f,cx+4f,cy+2f,paint)
                c.drawRect(cx+1f,cy-6f,cx+5f,cy+6f,paint)
                c.drawRect(cx+6f,cy-4f,cx+9f,cy+4f,paint)
            }
            "skill" -> {
                c.drawRect(cx-2f,cy-10f,cx+2f,cy+10f,paint)
                c.drawRect(cx-9f,cy-2f,cx+9f,cy+2f,paint)
                c.drawRect(cx-6f,cy-6f,cx+6f,cy+6f,paint)
            }
            "follow" -> { c.drawRect(cx-7f,cy-6f,cx+7f,cy+6f,paint); c.drawRect(cx-2f,cy-10f,cx+2f,cy+10f,paint) }
            "guard" -> { c.drawRect(cx-7f,cy-8f,cx+7f,cy-5f,paint); c.drawRect(cx-5f,cy-5f,cx+5f,cy+5f,paint); c.drawRect(cx-2f,cy+5f,cx+2f,cy+9f,paint) }
            "range" -> { c.drawCircle(cx,cy,7f,paint); paint.color=Color.rgb(20,34,30); c.drawCircle(cx,cy,3f,paint) }
        }
    }

    private fun drawHud(c: Canvas) {
        paint.color=Color.argb(238,9,18,22); c.drawRoundRect(RectF(12f,10f,442f,126f),7f,7f,paint)
        paint.color=Color.rgb(82,112,91); c.drawRect(18f,15f,436f,18f,paint)
        text(c,"STARFALL RPG",24f,35f,15f,Color.rgb(242,218,158))
        text(c,(playerName.ifBlank{"Adventurer"}+" · "+hero).take(26),24f,52f,10f,Color.rgb(195,220,211))
        text(c,"LV "+level,24f,69f,10f,Color.rgb(244,218,143)); text(c,"GOLD "+gold,91f,69f,10f,Color.rgb(244,218,143)); text(c,"KILL "+kills,178f,69f,10f); text(c,areaNames[activeAreaIndex].uppercase(),260f,69f,9f,Color.rgb(174,219,203))
        text(c,"HP",24f,87f,9f,Color.rgb(255,190,180)); bar(c,56f,78f,164f,11f,hp,maxHp,Color.rgb(209,61,65)); text(c,hp.toInt().toString()+"/"+maxHp.toInt(),226f,87f,9f)
        text(c,"MP",24f,103f,9f,Color.rgb(160,208,255)); bar(c,56f,94f,164f,11f,mana,maxMana,Color.rgb(52,132,230)); text(c,mana.toInt().toString()+"/"+maxMana.toInt(),226f,103f,9f)
        text(c,"GEMMA Lv.$gemmaLevel",276f,87f,9f,Color.rgb(230,183,255)); bar(c,326f,78f,92f,11f,gemmaHp,gemmaMaxHp,Color.rgb(173,91,209)); text(c,gemmaHp.toInt().toString()+"/"+gemmaMaxHp.toInt(),326f,103f,9f); bar(c,326f,108f,92f,7f,gemmaExp.toFloat(),gemmaNeed(gemmaLevel).toFloat(),Color.rgb(121,198,104))
        paint.color=Color.argb(230,9,18,22); c.drawRoundRect(RectF(452f,52f,690f,96f),6f,6f,paint)
        text(c,"EXP  "+exp+" / "+need(level),464f,68f,10f,Color.rgb(202,230,176)); bar(c,464f,75f,214f,13f,exp.toFloat(),need(level).toFloat(),Color.rgb(106,190,91))
        text(c,"DODGE "+(if(dodgeCooldown<=0f)"READY" else (dodgeCooldown.toInt()+1).toString()+"s"),464f,90f,9f,Color.rgb(239,224,185))
        listOf("FOLLOW","ATTACK","GUARD","RANGE").forEachIndexed { i,cmd ->
            val bx=554f+i*96f
            paint.color=Color.argb(100,0,0,0); c.drawRect(bx+2f,16f,bx+90f,48f,paint)
            paint.color=if(command==cmd)Color.rgb(75,74,116) else Color.rgb(34,52,47); c.drawRect(bx,12f,bx+88f,44f,paint)
            paint.color=if(command==cmd)Color.rgb(165,151,218) else Color.rgb(103,137,103); c.drawRect(bx+2f,14f,bx+86f,16f,paint)
            paint.color=Color.rgb(211,190,139); paint.style=Paint.Style.STROKE; c.drawRect(bx+0.5f,12.5f,bx+87.5f,43.5f,paint); paint.style=Paint.Style.FILL
            val iconKind=when(cmd){"FOLLOW"->"follow";"ATTACK"->"attack";"GUARD"->"guard";else->"range"}
            drawPixelIcon(c,iconKind,bx+16f,28f,Color.rgb(245,221,158)); text(c,cmd,bx+29f,33f,10f,Color.rgb(248,241,216))
        }
        if(messageTime>0f){paint.color=Color.argb(235,12,20,23);c.drawRoundRect(RectF(270f,470f,690f,510f),7f,7f,paint);text(c,message.take(54),284f,495f,12f,Color.rgb(255,235,189))}
        paint.color=Color.argb(135,190,210,190);c.drawCircle(88f,445f,51f,paint); paint.color=Color.argb(190,49,90,75);c.drawCircle(88f+moveX*20f,445f+moveY*20f,21f,paint);text(c,"MOVE",65f,510f,10f)
        paint.color=Color.argb(130,0,0,0); c.drawRect(634f,416f,728f,486f,paint); paint.color=Color.rgb(105,59,39); c.drawRect(631f,413f,725f,480f,paint)
        paint.color=Color.rgb(222,153,93); c.drawRect(633f,415f,723f,417f,paint); paint.color=Color.rgb(243,205,151); paint.style=Paint.Style.STROKE; c.drawRect(631.5f,413.5f,724.5f,479.5f,paint); paint.style=Paint.Style.FILL
        text(c,"⚔",668f,443f,23f,Color.rgb(255,225,164)); text(c,"ATTACK",646f,468f,11f,Color.rgb(255,242,218)); text(c,if(attackTimer>0f) "WAIT" else "READY",650f,476f,8f,Color.rgb(255,225,150))
        paint.color=Color.rgb(167,88,72); c.drawRect(738f,415f,818f,417f,paint); paint.color=Color.rgb(225,179,127); paint.style=Paint.Style.STROKE; c.drawRect(736.5f,413.5f,819.5f,479.5f,paint); paint.style=Paint.Style.FILL
        drawPixelIcon(c,"dodge",778f,440f,Color.rgb(255,224,168)); text(c,"DODGE",754f,468f,10f,Color.rgb(255,242,218))
        paint.color=Color.argb(130,0,0,0); c.drawRect(829f,416f,915f,486f,paint); paint.color=Color.rgb(48,42,88); c.drawRect(826f,413f,912f,480f,paint)
        paint.color=Color.rgb(113,91,171); c.drawRect(828f,415f,910f,417f,paint); paint.color=Color.rgb(190,171,224); paint.style=Paint.Style.STROKE; c.drawRect(826.5f,413.5f,911.5f,479.5f,paint); paint.style=Paint.Style.FILL
        drawPixelIcon(c,"skill",869f,438f,Color.rgb(198,230,255)); text(c,"ARC SLASH",840f,462f,9f,Color.rgb(246,239,255)); text(c,if(skillCooldown>0f)(skillCooldown.toInt()+1).toString()+"s" else "MP 25",852f,474f,9f,Color.rgb(255,225,150))
        paint.color=Color.argb(225,18,30,34); c.drawRoundRect(RectF(884f,55f,948f,85f),6f,6f,paint); text(c,"MENU",895f,75f,11f,Color.rgb(255,230,172))
    }

    private fun menuButton(c: Canvas, label: String, bx: Float, by: Float, bw: Float, bh: Float, active: Boolean = false) {
        paint.shader = null
        // Deep offset shadow, crisp one-pixel frame, inset highlight and darker lower bevel.
        paint.color = Color.argb(150, 0, 0, 0)
        c.drawRect(bx + 3f, by + 4f, bx + bw + 3f, by + bh + 4f, paint)
        paint.color = if (active) Color.rgb(73, 70, 113) else Color.rgb(32, 53, 47)
        c.drawRect(bx, by, bx + bw, by + bh, paint)
        paint.color = if (active) Color.rgb(120, 113, 171) else Color.rgb(64, 104, 78)
        c.drawRect(bx + 2f, by + 2f, bx + bw - 2f, by + 5f, paint)
        paint.color = Color.rgb(17, 29, 27)
        c.drawRect(bx + 2f, by + bh - 4f, bx + bw - 2f, by + bh - 2f, paint)
        paint.color = if (active) Color.rgb(226, 208, 158) else Color.rgb(112, 142, 111)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        c.drawRect(bx + 0.5f, by + 0.5f, bx + bw - 0.5f, by + bh - 0.5f, paint)
        paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
        val up = label.uppercase()
        val iconKind = when {
            up.contains("SKILL") || up.contains("ARC") -> "skill"
            up.contains("EQUIPMENT") || up.contains("SENJATA") || up.contains("ARMOR") || up.contains("BESI") -> "guard"
            up.contains("CHARACTER") || up.contains("HERO") || up.contains("PLAYER") -> "follow"
            up.contains("INVENTORY") || up.contains("RAMUAN") || up.contains("TOKO") -> "range"
            else -> "attack"
        }
        paint.color = Color.rgb(15, 27, 26)
        c.drawRect(bx + 7f, by + bh / 2f - 11f, bx + 29f, by + bh / 2f + 11f, paint)
        drawPixelIcon(c, iconKind, bx + 18f, by + bh / 2f, if (active) Color.rgb(255, 224, 157) else Color.rgb(194, 215, 170))
        val fontSize = when {
            label.length > 28 -> 9f
            label.length > 22 -> 10f
            label.length > 15 -> 11f
            else -> 13f
        }
        text(c, label, bx + 36f, by + bh * 0.62f, fontSize, Color.rgb(248, 242, 222))
    }

    private fun cityBlocked(ax: Float, ay: Float): Boolean {
        if (ax < 34f || ax > 926f || ay < 175f || ay > 454f) return true
        // Building footprints include roof overhangs so the party cannot walk through walls.
        val buildings = listOf(
            RectF(33f, 168f, 223f, 340f),
            RectF(267f, 172f, 453f, 340f),
            RectF(501f, 168f, 691f, 340f)
        )
        return buildings.any { it.contains(ax, ay) }
    }

    private fun updateCity(dt: Float) {
        tickFootsteps(dt)
        val speed = if (dodgeTime > 0f) 230f else 145f
        val nx = x + moveX * speed * dt; val ny = y + moveY * speed * dt
        if (!cityBlocked(nx, y)) x = nx
        if (!cityBlocked(x, ny)) y = ny
        val followX = if (command == "RANGE") x - 65f else x - 35f
        val followY = y + 28f
        gemmaX += (followX.coerceIn(30f, 925f) - gemmaX) * min(1f, dt * 3f)
        gemmaY += (followY.coerceIn(105f, 445f) - gemmaY) * min(1f, dt * 3f)
    }

    private fun drawCity(c: Canvas) {
        paint.shader = LinearGradient(0f,0f,0f,540f,Color.rgb(39,58,83),Color.rgb(205,142,93),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,960f,540f,paint); paint.shader = null
        // Distant hills and medieval castle wall: deliberately no modern skyline.
        paint.color = Color.rgb(52,78,75); c.drawOval(RectF(-110f,112f,300f,295f),paint); c.drawOval(RectF(190f,102f,610f,295f),paint); c.drawOval(RectF(540f,125f,1040f,300f),paint)
        paint.color = Color.rgb(72,72,69); c.drawRect(0f,174f,960f,292f,paint)
        paint.color = Color.rgb(103,94,78); c.drawRect(0f,184f,960f,292f,paint)
        for (i in 0..19) { val bx=i*49f; paint.color=if(i%2==0)Color.rgb(111,101,83) else Color.rgb(91,85,73); c.drawRect(bx,164f,bx+28f,190f,paint) }
        for (tx in listOf(90f,390f,700f,900f)) {
            paint.color=Color.rgb(72,67,60); c.drawRect(tx,132f,tx+52f,253f,paint)
            paint.color=Color.rgb(125,108,84); c.drawRect(tx+7f,143f,tx+45f,253f,paint)
            paint.color=Color.rgb(70,62,55); val roofPath=android.graphics.Path().apply{moveTo(tx-5f,143f);lineTo(tx+26f,105f);lineTo(tx+57f,143f);close()}; c.drawPath(roofPath,paint)
            paint.color=Color.rgb(218,174,106); c.drawRect(tx+17f,166f,tx+35f,190f,paint)
        }
        paint.shader=LinearGradient(0f,276f,0f,540f,Color.rgb(77,119,69),Color.rgb(38,76,49),Shader.TileMode.CLAMP); c.drawRect(0f,276f,960f,540f,paint); paint.shader=null
        paint.color=Color.rgb(119,105,84); c.drawRoundRect(RectF(0f,314f,960f,540f),6f,6f,paint)
        for(row in 0..12) for(col in 0..35) {
            val px=col*28f+(if(row%2==0)0f else 14f); val py=320f+row*17f
            paint.color=when((row*7+col*3)%4){0->Color.rgb(151,132,105);1->Color.rgb(130,115,93);2->Color.rgb(166,145,113);else->Color.rgb(142,125,101)}
            c.drawRoundRect(RectF(px,py,px+25f,py+13f),2f,2f,paint)
            paint.color=Color.argb(45,30,25,20); c.drawLine(px+2f,py+12f,px+23f,py+12f,paint)
        }
        for (i in 0..16) {
            val gx=(i*71f+19f)%950f; val gy=296f+(i%4)*47f
            paint.color=Color.rgb(33,80,42); c.drawOval(RectF(gx,gy,gx+43f,gy+22f),paint)
            paint.color=Color.rgb(68,132,55); c.drawCircle(gx+10f,gy+6f,5f,paint); c.drawCircle(gx+24f,gy+4f,6f,paint)
            if(i%3==0){paint.color=Color.rgb(228,188,110);c.drawCircle(gx+30f,gy+12f,2.5f,paint)}
        }
        fun house(bx:Float,by:Float,w:Float,wall:Int,roof:Int,sign:String) {
            val h=126f
            paint.color=Color.argb(90,0,0,0); c.drawRoundRect(RectF(bx+7f,by+12f,bx+w+9f,by+h+12f),5f,5f,paint)
            paint.color=Color.rgb(57,42,31); c.drawRect(bx,by+18f,bx+w,by+h,paint)
            paint.color=wall; c.drawRect(bx+7f,by+27f,bx+w-7f,by+h-4f,paint)
            paint.color=Color.rgb(74,45,30); paint.strokeWidth=7f
            c.drawLine(bx+10f,by+31f,bx+w-10f,by+31f,paint); c.drawLine(bx+12f,by+31f,bx+12f,by+h-6f,paint); c.drawLine(bx+w-12f,by+31f,bx+w-12f,by+h-6f,paint)
            c.drawLine(bx+15f,by+56f,bx+w-15f,by+88f,paint); c.drawLine(bx+w-15f,by+56f,bx+15f,by+88f,paint); paint.strokeWidth=1f
            val rp=android.graphics.Path().apply{moveTo(bx-13f,by+24f);lineTo(bx+w/2f,by-39f);lineTo(bx+w+13f,by+24f);close()}
            paint.color=Color.rgb(54,35,29); c.drawPath(rp,paint); c.save(); c.clipPath(rp)
            paint.shader=LinearGradient(bx,by-38f,bx+w,by+28f,roof,Color.rgb(76,43,36),Shader.TileMode.CLAMP); c.drawRect(bx-16f,by-42f,bx+w+16f,by+28f,paint); paint.shader=null
            for(r in 0..5){paint.color=Color.argb(85,20,14,13);c.drawLine(bx-10f,by-23f+r*10f,bx+w+10f,by-23f+r*10f,paint)}; c.restore()
            paint.color=Color.rgb(56,35,26); c.drawRect(bx+w/2f-17f,by+76f,bx+w/2f+17f,by+h,paint)
            paint.color=Color.rgb(129,80,45); c.drawRect(bx+w/2f-12f,by+81f,bx+w/2f+12f,by+h,paint)
            paint.color=Color.rgb(239,193,105); c.drawCircle(bx+w/2f+7f,by+99f,2.5f,paint)
            for(wx in listOf(bx+19f,bx+w-39f)){paint.color=Color.rgb(69,43,30);c.drawRect(wx,by+48f,wx+20f,by+72f,paint);paint.color=Color.rgb(246,203,116);c.drawRect(wx+3f,by+51f,wx+17f,by+69f,paint);paint.color=Color.rgb(98,78,48);c.drawLine(wx+10f,by+51f,wx+10f,by+69f,paint);c.drawLine(wx+3f,by+60f,wx+17f,by+60f,paint)}
            paint.color=Color.rgb(95,76,59); c.drawRect(bx+w/2f-22f,by+h-3f,bx+w/2f+22f,by+h+5f,paint)
            text(c,sign,bx+8f,by+15f,10f,Color.rgb(255,235,188))
        }
        house(46f,207f,164f,Color.rgb(183,143,94),Color.rgb(137,59,45),"THE WAYFARER")
        house(280f,211f,160f,Color.rgb(163,132,91),Color.rgb(91,49,37),"GENERAL GOODS")
        house(514f,207f,164f,Color.rgb(177,154,112),Color.rgb(111,50,43),"IRON & EMBER")
        // Medieval well, market crates and notice board.
        paint.color=Color.rgb(72,59,44); c.drawRect(733f,292f,801f,329f,paint); paint.color=Color.rgb(143,129,102); c.drawRect(738f,295f,796f,322f,paint); paint.color=Color.rgb(48,65,65); c.drawOval(RectF(744f,296f,790f,312f),paint)
        paint.color=Color.rgb(115,75,42); c.drawRect(716f,272f,722f,322f,paint); c.drawRect(808f,272f,814f,322f,paint); c.drawRect(705f,270f,825f,276f,paint)
        paint.color=Color.rgb(92,60,37); c.drawRect(680f,343f,716f,371f,paint); c.drawRect(721f,350f,750f,374f,paint); paint.color=Color.rgb(171,120,65); c.drawRect(684f,347f,712f,366f,paint); c.drawRect(725f,354f,746f,369f,paint)
        paint.color=Color.rgb(70,45,30); c.drawRect(664f,286f,671f,337f,paint); paint.color=Color.rgb(155,100,47); c.drawRect(648f,284f,698f,301f,paint); text(c,"NOTICE",652f,296f,7f,Color.rgb(255,231,174))
        // Party, enemies, combat effects and controls are drawn above this map.
    }


    private fun drawBattleArena(c: Canvas) {
        // Battle Asterfall is a distinct, invaded variant of the safe city: same medieval
        // architecture, but darker lighting, damaged paving and marked monster entry lanes.
        drawCity(c)
        paint.shader = LinearGradient(0f, 130f, 0f, 540f,
            Color.argb(22, 48, 18, 25), Color.argb(92, 34, 12, 20), Shader.TileMode.CLAMP)
        c.drawRect(0f, 130f, 960f, 540f, paint)
        paint.shader = null
        val scorch = listOf(
            RectF(24f, 352f, 84f, 394f),
            RectF(188f, 420f, 260f, 452f),
            RectF(365f, 350f, 425f, 384f),
            RectF(570f, 421f, 650f, 452f),
            RectF(850f, 354f, 932f, 400f)
        )
        scorch.forEachIndexed { i, r ->
            paint.color = Color.argb(58, 35, 23, 22)
            c.drawOval(r, paint)
            paint.color = Color.argb(65, 151, 67, 45)
            c.drawOval(RectF(r.left + 8f, r.top + 6f, r.right - 8f, r.bottom - 7f), paint)
            if (i % 2 == 0) {
                paint.color = Color.argb(95, 202, 139, 75)
                c.drawLine(r.centerX() - 8f, r.centerY() - 5f, r.centerX() + 10f, r.centerY() + 6f, paint)
                c.drawLine(r.centerX() + 10f, r.centerY() + 6f, r.centerX() + 17f, r.centerY() - 2f, paint)
            }
        }
        // Glowing entry seals indicate where enemies are advancing from either side.
        listOf(42f, 918f).forEach { sx ->
            paint.color = Color.argb(42, 235, 72, 54)
            c.drawCircle(sx, 390f, 30f + kotlin.math.sin(clock * 3f) * 2f, paint)
            paint.color = Color.argb(185, 205, 80, 58)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            c.drawCircle(sx, 390f, 19f, paint)
            c.drawCircle(sx, 390f, 10f, paint)
            c.drawLine(sx - 24f, 390f, sx + 24f, 390f, paint)
            c.drawLine(sx, 366f, sx, 414f, paint)
            paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
        }
        text(c, "INVASION ZONE", 786f, 112f, 10f, Color.rgb(255, 191, 154))
    }

    private fun drawCityCharacters(c: Canvas) {
        fun citizen(cx: Float, feetY: Float, robe: Int, hair: Int, label: String) {
            paint.color = Color.argb(90, 0, 0, 0)
            c.drawOval(RectF(cx - 17f, feetY - 2f, cx + 18f, feetY + 7f), paint)
            paint.color = robe
            c.drawRoundRect(RectF(cx - 13f, feetY - 31f, cx + 13f, feetY - 5f), 4f, 4f, paint)
            paint.color = Color.rgb(60, 43, 31)
            c.drawRect(cx - 12f, feetY - 8f, cx - 4f, feetY, paint)
            c.drawRect(cx + 4f, feetY - 8f, cx + 12f, feetY, paint)
            paint.color = Color.rgb(224, 181, 133)
            c.drawCircle(cx, feetY - 39f, 10f, paint)
            paint.color = hair
            c.drawArc(RectF(cx - 10f, feetY - 49f, cx + 10f, feetY - 31f), 180f, 180f, true, paint)
            paint.color = Color.rgb(42, 35, 29)
            c.drawCircle(cx - 3f, feetY - 39f, 1.3f, paint)
            c.drawCircle(cx + 3f, feetY - 39f, 1.3f, paint)
            paint.color = Color.rgb(211, 192, 151)
            c.drawRect(cx - 15f, feetY - 30f, cx + 15f, feetY - 27f, paint)
            text(c, label, cx - label.length * 2.6f, feetY - 55f, 8f, Color.rgb(255, 232, 177))
        }
        // Town NPCs exist only in the safe city. They are not drawn in the battle arena.
        citizen(245f, 382f, Color.rgb(65, 102, 129), Color.rgb(65, 43, 29), "PENJAGA")
        citizen(455f, 385f, Color.rgb(123, 83, 143), Color.rgb(211, 211, 203), "TABIB")
        citizen(710f, 388f, Color.rgb(149, 102, 49), Color.rgb(77, 45, 28), "PEDAGANG")
        citizen(850f, 360f, Color.rgb(90, 103, 72), Color.rgb(52, 37, 27), "PANDAI BESI")
        paint.color = Color.argb(85, 0, 0, 0)
        c.drawOval(RectF(gemmaX - 18f, gemmaY + 4f, gemmaX + 19f, gemmaY + 15f), paint)
        val gemmaMoving = dist(gemmaX, gemmaY, x, y) > 54f
        drawAnimatedCharacter(c, gemmaSheet, gemmaX, gemmaY + 5f, gemmaMoving,
            facingColumn(x - gemmaX, y - gemmaY), 43f, 48f)
        text(c, "GEMMA", gemmaX - 23f, gemmaY - 49f, 9f, Color.rgb(235, 202, 255))
        paint.color = Color.argb(85, 0, 0, 0)
        c.drawOval(RectF(x - 17f, y + 6f, x + 18f, y + 17f), paint)
        val moving = kotlin.math.abs(moveX) + kotlin.math.abs(moveY) > 0.08f
        drawAnimatedCharacter(c, heroSheet, x, y + 5f, moving,
            if (moving) facingColumn(moveX, moveY) else 0, 47f, 51f)
    }

    private fun drawCityHud(c: Canvas) {
        paint.color = Color.argb(232, 9, 18, 22)
        c.drawRoundRect(RectF(12f, 10f, 430f, 94f), 7f, 7f, paint)
        paint.color = Color.rgb(82, 112, 91)
        c.drawRect(18f, 15f, 424f, 18f, paint)
        text(c, "ASTERFALL CITY · SAFE ZONE", 24f, 37f, 15f, Color.rgb(242, 218, 158))
        text(c, (playerName.ifBlank { "Adventurer" } + " · " + hero).take(30), 24f, 56f, 10f, Color.rgb(195, 220, 211))
        text(c, "LV $level   GOLD $gold", 24f, 76f, 11f, Color.rgb(244, 218, 143))
        paint.color = Color.argb(220, 9, 18, 22)
        c.drawRoundRect(RectF(450f, 10f, 698f, 62f), 6f, 6f, paint)
        text(c, "HP", 462f, 30f, 9f, Color.rgb(255, 190, 180))
        bar(c, 490f, 21f, 190f, 11f, hp, maxHp, Color.rgb(209, 61, 65))
        text(c, "EXP", 462f, 49f, 9f, Color.rgb(202, 230, 176))
        bar(c, 490f, 39f, 190f, 11f, exp.toFloat(), need(level).toFloat(), Color.rgb(106, 190, 91))
        paint.color = Color.argb(220, 9, 18, 22)
        c.drawRoundRect(RectF(714f, 10f, 948f, 62f), 6f, 6f, paint)
        text(c, "KOTA AMAN", 735f, 31f, 12f, Color.rgb(183, 211, 177))
        text(c, "NPC: sentuh untuk bicara", 735f, 49f, 10f, Color.rgb(195, 220, 211))
        // Movement joystick stays in the lower-left, away from city action buttons.
        paint.color = Color.argb(135, 190, 210, 190)
        c.drawCircle(88f, 445f, 51f, paint)
        paint.color = Color.argb(190, 49, 90, 75)
        c.drawCircle(88f + moveX * 20f, 445f + moveY * 20f, 21f, paint)
        text(c, "MOVE", 65f, 510f, 10f)
        menuButton(c, "TOKO", 530f, 472f, 88f, 46f)
        menuButton(c, "EQUIP", 623f, 472f, 100f, 46f)
        menuButton(c, "BATTLE", 728f, 472f, 88f, 46f, true)
        menuButton(c, "MENU", 821f, 472f, 95f, 46f)
        if (messageTime > 0f) {
            paint.color = Color.argb(232, 12, 20, 23)
            c.drawRoundRect(RectF(280f, 420f, 680f, 458f), 7f, 7f, paint)
            text(c, message.take(52), 294f, 444f, 11f, Color.rgb(255, 235, 189))
        }
    }

    private fun drawMenu(c: Canvas) {
        c.drawColor(Color.rgb(12, 24, 25))
        // Dark forest-grain backdrop, kept subtle so panels and labels stay readable.
        for (i in 0..59) {
            paint.color = if (i % 2 == 0) Color.rgb(19, 37, 34) else Color.rgb(16, 32, 31)
            c.drawRect(i * 16f, 0f, i * 16f + 16f, 540f, paint)
        }
        paint.color = Color.argb(245, 8, 17, 21)
        c.drawRoundRect(RectF(22f, 16f, 938f, 524f), 10f, 10f, paint)
        paint.color = Color.rgb(87, 116, 91); c.drawRect(34f, 28f, 926f, 31f, paint)
        text(c, "STARFALL RPG", 48f, 61f, 23f, Color.rgb(242, 218, 158))
        text(c, screen, 50f, 88f, 12f, Color.rgb(159, 197, 166))
        val status = playerName.ifBlank { "Nama belum dibuat" } + "  ·  " + hero + "  ·  LV " + level + "  ·  GOLD " + gold
        text(c, status.take(46), 485f, 60f, 11f, Color.rgb(245, 218, 146))
        // Two separated content panels establish a consistent hierarchy on every screen.
        paint.color = Color.rgb(17, 31, 31); c.drawRoundRect(RectF(38f, 105f, 458f, 494f), 7f, 7f, paint)
        paint.color = Color.rgb(27, 43, 39); c.drawRect(40f, 107f, 456f, 110f, paint)
        if (screen != "MAIN" && screen != "PAUSE") {
            paint.color = Color.rgb(15, 28, 29); c.drawRoundRect(RectF(478f, 105f, 918f, 494f), 7f, 7f, paint)
            paint.color = Color.rgb(70, 97, 76); c.drawRect(480f, 107f, 916f, 110f, paint)
        }
        when (screen) {
            "MAIN" -> {
                text(c, "PETUALANGAN", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                menuButton(c, "MULAI PETUALANGAN", 52f, 145f, 390f, 49f, playerName.isNotBlank())
                menuButton(c, "CREATE PLAYER / PILIH HERO", 52f, 207f, 390f, 46f)
                menuButton(c, "CHARACTER", 52f, 270f, 185f, 44f)
                menuButton(c, "INVENTORY", 252f, 270f, 190f, 44f)
                menuButton(c, "SKILLS", 52f, 326f, 185f, 44f)
                menuButton(c, "EQUIPMENT", 252f, 326f, 190f, 44f)
                menuButton(c, "CAMPAIGN · 50 STAGE", 52f, 382f, 185f, 44f, true)
                menuButton(c, "SETTINGS", 252f, 382f, 190f, 44f)
                paint.color = Color.rgb(21, 39, 37); c.drawRoundRect(RectF(490f, 121f, 900f, 293f), 6f, 6f, paint)
                text(c, "JELAJAHI ASTERFALL", 512f, 154f, 16f, Color.rgb(241, 216, 153))
                text(c, "Bangun hero, kumpulkan perlengkapan,", 512f, 188f, 12f)
                text(c, "dan taklukkan monster di jalanan Asterfall.", 512f, 209f, 12f)
                text(c, "Vanguard · HP tinggi", 512f, 244f, 11f, Color.rgb(183, 211, 177))
                text(c, "Ranger · damage tinggi  |  Mage · skill area", 512f, 265f, 10f, Color.rgb(183, 211, 177))
                menuButton(c, "KOTA ASTERFALL", 510f, 326f, 340f, 52f, true)
                text(c, "Offline  ·  Save lokal  ·  Pixel fantasy RPG", 510f, 412f, 11f, Color.rgb(144, 177, 155))
                text(c, "Progres tersimpan otomatis di perangkat.", 510f, 435f, 10f, Color.rgb(144, 177, 155))
                text(c, "50 stage · 5 wilayah · Elite Boss tiap 10 stage", 510f, 455f, 10f, Color.rgb(241, 216, 153))
                menuButton(c, "STATUS GEMMA", 510f, 386f, 340f, 42f)
            }
            "CAMPAIGN" -> {
                text(c, "PILIH STAGE · BOS WAJIB DIKALAHKAN", 52f, 132f, 12f, Color.rgb(241, 216, 153))
                text(c, "Terbuka: 1-$unlockedStage · Stage aktif: $campaignStage · Kill: $stageKills/30", 52f, 151f, 11f, Color.rgb(185, 215, 190))
                for (stage in 1..50) {
                    val col = (stage - 1) % 10; val row = (stage - 1) / 10
                    val bx = 52f + col * 86f; val by = 166f + row * 55f
                    val open = stage <= unlockedStage; val elite = stage % 10 == 0
                    paint.color = when { !open -> Color.rgb(34,39,39); elite -> Color.rgb(102,54,43); stage == campaignStage -> Color.rgb(60,91,62); else -> Color.rgb(31,56,47) }
                    c.drawRoundRect(RectF(bx,by,bx+78f,by+45f),5f,5f,paint)
                    paint.color = if (open) Color.rgb(129,158,109) else Color.rgb(72,78,76)
                    paint.style = Paint.Style.STROKE; c.drawRoundRect(RectF(bx,by,bx+78f,by+45f),5f,5f,paint); paint.style = Paint.Style.FILL
                    text(c, if (open) "STAGE $stage" else "LOCK $stage", bx+6f, by+17f, 10f, if(open) Color.WHITE else Color.LTGRAY)
                    text(c, if(elite) "ELITE BOSS" else if(open) "BOSS" else "LOCKED", bx+6f, by+34f, 9f, if(elite) Color.rgb(255,190,123) else Color.rgb(178,202,177))
                }
                menuButton(c, "KEMBALI", 52f, 455f, 180f, 42f)
                text(c, "Map: " + regionNames[(campaignStage-1)/10] + " · 30 kill memanggil bos.", 250f, 480f, 11f, Color.rgb(194,218,186))
            }
            "GEMMA" -> {
                text(c, "STATUS & KEMAMPUAN GEMMA", 54f, 132f, 12f, Color.rgb(230,183,255))
                text(c, "Level: $gemmaLevel / 100", 56f, 170f, 15f)
                text(c, "EXP: $gemmaExp / " + gemmaNeed(gemmaLevel), 56f, 201f, 13f)
                bar(c, 56f, 214f, 350f, 16f, gemmaExp.toFloat(), gemmaNeed(gemmaLevel).toFloat(), Color.rgb(121,198,104))
                text(c, "HP: " + gemmaHp.toInt() + " / " + gemmaMaxHp.toInt(), 56f, 260f, 13f)
                text(c, "Serangan otomatis: Arcane Bolt", 56f, 294f, 12f, Color.rgb(210,194,245))
                text(c, "Damage: " + (10 + (gemmaLevel-1)*2), 56f, 321f, 12f)
                text(c, "Skill: Ledakan tombak area", 56f, 350f, 12f)
                text(c, "Perintah: FOLLOW · ATTACK · GUARD · RANGE", 56f, 379f, 11f)
                menuButton(c, "KEMBALI", 52f, 420f, 190f, 44f)
                text(c, "EXP Gemma didapat dari monster dan bos.", 500f, 151f, 12f, Color.rgb(184,207,175))
                text(c, "Naik level meningkatkan HP maksimum Gemma.", 500f, 184f, 11f)
            }
            "CREATE" -> {
                text(c, "PROFIL PEMAIN", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                menuButton(c, if (playerName.isBlank()) "ISI NAMA PEMAIN" else "NAMA: " + playerName.take(16), 52f, 145f, 370f, 46f)
                menuButton(c, "SIMPAN KARAKTER", 52f, 207f, 370f, 46f, true)
                menuButton(c, "KEMBALI", 52f, 269f, 200f, 44f)
                text(c, "PILIH KELAS", 500f, 132f, 12f, Color.rgb(186, 207, 169))
                menuButton(c, "VANGUARD · HP TINGGI", 500f, 145f, 380f, 46f, hero == "Vanguard")
                menuButton(c, "RANGER · DAMAGE TINGGI", 500f, 207f, 380f, 46f, hero == "Ranger")
                menuButton(c, "MAGE · ARC SLASH KUAT", 500f, 269f, 380f, 46f, hero == "Mage")
                paint.color = Color.rgb(25, 42, 40); c.drawRoundRect(RectF(500f, 340f, 880f, 407f), 5f, 5f, paint)
                text(c, if (hero == "Vanguard") "HP 140  ·  damage seimbang" else if (hero == "Ranger") "HP 110  ·  damage lebih tinggi" else "HP 90  ·  skill area lebih kuat", 518f, 369f, 12f, Color.rgb(233, 212, 159))
                text(c, "Pilihan kelas bisa diganti kapan saja.", 518f, 390f, 10f, Color.rgb(170, 194, 169))
            }
            "SHOP" -> {
                text(c,"TOKO PEDAGANG",54f,132f,12f,Color.rgb(186,207,169))
                text(c,"Gold tersedia: "+gold,56f,165f,14f,Color.rgb(244,218,143))
                menuButton(c,"BELI RAMUAN HP · 20 GOLD",52f,183f,370f,46f,gold>=20)
                menuButton(c,"BELI MATERIAL · 35 GOLD",52f,245f,370f,46f,gold>=35)
                menuButton(c,"KEMBALI KE KOTA",52f,382f,220f,44f)
                text(c,"INVENTORY",500f,151f,12f,Color.rgb(186,207,169))
                text(c,"Ramuan HP: "+potions,500f,184f,13f,Color.rgb(230,155,147))
                text(c,"Material: "+materials,500f,210f,13f,Color.rgb(190,214,167))
                text(c,"Ramuan memulihkan 45 HP.",500f,252f,11f)
                text(c,"Material untuk crafting pada update berikutnya.",500f,276f,10f)
            }
            "CHARACTER" -> {
                text(c, "STATUS KARAKTER", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                text(c, "Nama: " + playerName.ifBlank { "Belum dibuat" }, 56f, 160f, 14f)
                text(c, "Kelas: " + hero + "   ·   Level: " + level, 56f, 197f, 13f)
                text(c, "EXP: " + exp + " / " + need(level), 56f, 231f, 13f)
                text(c, "HP: " + hp.toInt() + " / " + maxHp.toInt(), 56f, 265f, 13f, Color.rgb(230, 155, 147))
                text(c, "Damage: " + (damage.toInt() + weaponLevel * 5), 56f, 299f, 13f)
                text(c, "Monster dikalahkan: " + kills, 56f, 333f, 13f)
                menuButton(c, "GANTI HERO", 52f, 382f, 185f, 44f)
                menuButton(c, "KEMBALI", 252f, 382f, 190f, 44f)
                text(c, "Statistik akan mengikuti level dan equipment.", 500f, 151f, 12f, Color.rgb(184, 207, 175))
                bar(c, 510f, 184f, 340f, 15f, hp, maxHp, Color.rgb(204, 74, 76))
                text(c, "HP HERO", 510f, 176f, 10f)
                bar(c, 510f, 225f, 340f, 15f, gemmaHp, gemmaMaxHp, Color.rgb(166, 100, 206))
                text(c, "GEMMA", 510f, 217f, 10f)
                text(c, "Weapon +"+(weaponLevel*5)+" damage", 510f, 278f, 12f)
                text(c, "Armor -"+(armorLevel*3)+" damage diterima", 510f, 303f, 12f)
            }
            "INVENTORY" -> {
                text(c, "BARANG BAWAAN", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                text(c, "Ramuan HP  ×  " + potions, 56f, 166f, 15f, Color.rgb(230, 155, 147))
                text(c, "Memulihkan 45 HP saat digunakan.", 56f, 192f, 11f)
                text(c, "Material umum  ×  " + materials, 56f, 235f, 14f, Color.rgb(190, 214, 167))
                text(c, "Gold  ·  " + gold, 56f, 278f, 14f, Color.rgb(244, 218, 143))
                menuButton(c, "GUNAKAN RAMUAN", 52f, 326f, 245f, 46f, potions > 0 && hp < maxHp)
                menuButton(c, "KEMBALI", 52f, 390f, 190f, 44f)
                text(c, "Drop monster bisa berisi ramuan dan material.", 500f, 151f, 12f, Color.rgb(184, 207, 175))
                text(c, "Kalahkan monster untuk mendapat EXP dan gold.", 500f, 184f, 11f)
                text(c, "Gunakan ramuan saat HP hero berkurang.", 500f, 210f, 11f)
            }
            "SKILLS" -> {
                text(c, "SKILL AKTIF", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                text(c, "ARC SLASH  ·  Level " + skillLevel + " / 5", 56f, 166f, 16f, Color.rgb(211, 176, 244))
                text(c, "Damage area: " + (42 + (skillLevel - 1) * 18 + weaponLevel * 5), 56f, 199f, 13f)
                text(c, "Cooldown: 6 detik", 56f, 228f, 12f)
                menuButton(c, "UPGRADE ARC SLASH · " + (skillLevel * 45) + " GOLD", 52f, 258f, 370f, 46f, skillLevel < 5 && gold >= skillLevel * 45)
                menuButton(c, "KEMBALI", 52f, 382f, 190f, 44f)
                text(c, "Upgrade menambah damage serangan area.", 500f, 151f, 12f, Color.rgb(184, 207, 175))
                text(c, "Dekati musuh lalu tekan tombol Arc Slash.", 500f, 184f, 11f)
                text(c, "Gunakan Dodge untuk menghindari serangan.", 500f, 210f, 11f)
            }
            "EQUIPMENT" -> {
                text(c, "PENGUATAN PERLENGKAPAN", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                text(c, "Senjata  ·  +" + (weaponLevel * 5) + " damage", 56f, 165f, 14f)
                menuButton(c, "UPGRADE SENJATA · " + ((weaponLevel + 1) * 55) + " GOLD", 52f, 183f, 370f, 44f, weaponLevel < 5 && gold >= (weaponLevel + 1) * 55)
                text(c, "Armor  ·  pengurangan damage " + (armorLevel * 3), 56f, 264f, 13f)
                menuButton(c, "UPGRADE ARMOR · " + ((armorLevel + 1) * 50) + " GOLD", 52f, 281f, 370f, 44f, armorLevel < 5 && gold >= (armorLevel + 1) * 50)
                menuButton(c, "KEMBALI", 52f, 382f, 190f, 44f)
                text(c, "Upgrade memakai gold dari hasil berburu.", 500f, 151f, 12f, Color.rgb(184, 207, 175))
                text(c, "Setiap level senjata menambah damage.", 500f, 184f, 11f)
                text(c, "Setiap level armor mengurangi damage masuk.", 500f, 210f, 11f)
                text(c, "Maksimum upgrade: level 5.", 500f, 236f, 11f)
            }
            "QUESTS" -> {
                text(c, "TARGET PERBURUAN", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                text(c, (if (kills >= 5) "[X]" else "[ ]") + " Pemburu pemula  ·  " + kills + "/5 monster", 56f, 170f, 13f)
                text(c, (if (kills >= 18) "[X]" else "[ ]") + " Tantang Guardian  ·  " + kills + "/18 monster", 56f, 211f, 12f)
                text(c, (if (level >= 5) "[X]" else "[ ]") + " Tumbuh kuat  ·  capai level 5", 56f, 252f, 12f)
                menuButton(c, "KEMBALI", 52f, 382f, 190f, 44f)
                text(c, "LOKASI", 500f, 151f, 12f, Color.rgb(186, 207, 169))
                text(c, "Kota Asterfall", 500f, 184f, 15f, Color.rgb(191, 218, 172))
                text(c, "Bos Campaign muncul setelah 30 monster biasa.", 500f, 218f, 11f)
            }
            "SETTINGS" -> {
                text(c, "PENGATURAN", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                text(c, "Mode offline · progres tersimpan lokal.", 56f, 170f, 13f)
                text(c, "Joystick kiri · Dodge kanan · Arc Slash.", 56f, 207f, 12f)
                menuButton(c, "EKSPOR SAVE", 52f, 236f, 320f, 44f)
                menuButton(c, "IMPOR SAVE", 52f, 290f, 320f, 44f)
                menuButton(c, "KEMBALI KE MENU UTAMA", 52f, 360f, 320f, 46f)
                text(c, "SAVE PROGRES", 500f, 151f, 12f, Color.rgb(186, 207, 169))
                text(c, "Backup JSON versi 1 bisa disalin.", 500f, 184f, 11f)
                text(c, "Save lama tanpa nomor versi tetap dibaca.", 500f, 210f, 11f)
                text(c, "Impor save memulihkan level, gold,", 500f, 244f, 11f)
                text(c, "equipment, item, HP dan progres.", 500f, 264f, 11f)
            }
            "PAUSE" -> {
                text(c, "PERMAINAN DIJEDA", 54f, 132f, 12f, Color.rgb(186, 207, 169))
                menuButton(c, "LANJUT BERMAIN", 52f, 150f, 370f, 44f, true)
                menuButton(c, "CHARACTER", 52f, 208f, 180f, 44f)
                menuButton(c, "GEMMA", 242f, 208f, 180f, 44f)
                menuButton(c, "INVENTORY", 52f, 266f, 180f, 44f)
                menuButton(c, "SKILLS", 52f, 324f, 180f, 44f)
                menuButton(c, "EQUIPMENT", 242f, 324f, 180f, 44f)
                menuButton(c, "CAMPAIGN", 52f, 382f, 180f, 44f)
                menuButton(c, "MENU UTAMA", 242f, 382f, 180f, 44f)
                text(c, "Progress otomatis tersimpan.", 500f, 151f, 12f, Color.rgb(184, 207, 175))
                text(c, "Kamu bisa lanjut kapan saja.", 500f, 184f, 11f)
            }
        }
    }

    private fun showNameDialog() {
        val field=EditText(context);field.setSingleLine(true);field.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        field.setText(playerName);field.hint="Nama karakter"
        AlertDialog.Builder(context).setTitle("Create Player").setMessage("Masukkan nama hero (maksimal 18 karakter).")
            .setView(field).setNegativeButton("Batal",null).setPositiveButton("Simpan"){_,_->
                val v=field.text.toString().trim().take(18)
                if(v.isNotBlank()){playerName=v;save();screen="CREATE";invalidate()}
            }.show()
    }
    private fun clickMenu(tx:Float,ty:Float) {
        sfx("ui", ToneGenerator.TONE_PROP_BEEP)
        fun hit(a:Float,b:Float,w:Float,h:Float)=tx in a..(a+w)&&ty in b..(b+h)
        when(screen) {
            "MAIN" -> when {
                hit(52f,145f,390f,49f) -> if(playerName.isNotBlank()) startCampaignStage(campaignStage) else screen="CREATE"
                hit(52f,207f,390f,46f) -> screen="CREATE"
                hit(52f,270f,185f,44f) -> screen="CHARACTER"
                hit(252f,270f,190f,44f) -> screen="INVENTORY"
                hit(52f,326f,185f,44f) -> screen="SKILLS"
                hit(252f,326f,190f,44f) -> {equipmentReturnScreen="MAIN";screen="EQUIPMENT"}
                hit(52f,382f,185f,44f) -> screen="CAMPAIGN"
                hit(252f,382f,190f,44f) -> screen="SETTINGS"
                hit(510f,326f,340f,52f) -> {screen="CITY";x=470f;y=370f;gemmaX=425f;gemmaY=395f;moveX=0f;moveY=0f;cityMessage="Asterfall City: zona aman.";say(cityMessage,2.5f);sfx("door",ToneGenerator.TONE_PROP_ACK)}
                hit(510f,386f,340f,42f) -> screen="GEMMA"
            }
            "CAMPAIGN" -> {
                if (hit(52f,455f,180f,42f)) screen = "MAIN"
                else {
                    val col = ((tx - 52f) / 86f).toInt(); val row = ((ty - 166f) / 55f).toInt()
                    if (tx >= 52f && col in 0..9 && row in 0..4 && ty >= 166f && ty < 441f) {
                        val chosen = row * 10 + col + 1
                        if (chosen <= unlockedStage) startCampaignStage(chosen)
                        else say("Stage $chosen terkunci. Kalahkan bos Stage " + (chosen-1) + " dulu!", 2.5f)
                    }
                }
            }
            "GEMMA" -> if (hit(52f,420f,190f,44f)) screen = "MAIN"
            "CITY" -> when {
                hit(530f,472f,88f,46f) -> {screen="SHOP";moveX=0f;moveY=0f;say("Pedagang Asterfall siap melayani.",2f)}
                hit(623f,472f,100f,46f) -> {equipmentReturnScreen="CITY";screen="EQUIPMENT";moveX=0f;moveY=0f;say("Pandai besi: pilih upgrade perlengkapanmu.",2f)}
                hit(728f,472f,88f,46f) -> {screen="GAME";x=470f;y=370f;gemmaX=425f;gemmaY=395f;moveX=0f;moveY=0f;say("Keluar dari kota! Monster menyerang dari sisi arena.",3f);sfx("door",ToneGenerator.TONE_PROP_ACK)}
                hit(821f,472f,95f,46f) -> {screen="MAIN";moveX=0f;moveY=0f}
                hit(195f,325f,105f,95f) -> say("Penjaga kota: jalanan aman, tetapi ancaman monster datang dari luar tembok.",3f)
                hit(405f,325f,105f,95f) -> say("Tabib: simpan ramuan untuk saat HP-mu menipis.",3f)
                hit(665f,325f,105f,95f) -> {screen="SHOP";moveX=0f;moveY=0f}
                hit(805f,305f,90f,105f) -> {equipmentReturnScreen="CITY";screen="EQUIPMENT";moveX=0f;moveY=0f}
            }
            "CREATE" -> when {
                hit(52f,145f,370f,46f) -> showNameDialog()
                hit(500f,145f,380f,46f) -> {hero="Vanguard";maxHp=140f;damage=14f+(level-1)*2f;hp=min(hp,maxHp);save()}
                hit(500f,207f,380f,46f) -> {hero="Ranger";maxHp=110f;damage=18f+(level-1)*2f;hp=min(hp,maxHp);save()}
                hit(500f,269f,380f,46f) -> {hero="Mage";maxHp=90f;damage=14f+(level-1)*2f;hp=min(hp,maxHp);save()}
                hit(52f,207f,370f,46f) -> if(playerName.isNotBlank()){when(hero){"Vanguard"->{maxHp=140f;damage=14f+(level-1)*2f};"Ranger"->{maxHp=110f;damage=18f+(level-1)*2f};else->{maxHp=90f;damage=14f+(level-1)*2f}};hp=maxHp;save();screen="MAIN"}else showNameDialog()
                hit(52f,269f,200f,44f) -> screen="MAIN"
            }
            "SHOP" -> when {
                hit(52f,183f,370f,46f) -> if(gold>=20){gold-=20;potions++;save();say("Ramuan HP dibeli.",2f)}else say("Gold tidak cukup.")
                hit(52f,245f,370f,46f) -> if(gold>=35){gold-=35;materials++;save();say("Material dibeli.",2f)}else say("Gold tidak cukup.")
                hit(52f,382f,220f,44f) -> screen="CITY"
            }
            "CHARACTER" -> when {hit(52f,382f,185f,44f)->screen="CREATE";hit(252f,382f,190f,44f)->screen="MAIN"}
            "INVENTORY" -> when {
                hit(52f,326f,245f,46f) -> if(potions>0&&hp<maxHp){potions--;hp=min(maxHp,hp+45f);save();say("Ramuan digunakan: +45 HP",2f)}else say(if(potions==0)"Ramuan habis." else "HP masih penuh.")
                hit(52f,390f,190f,44f) -> screen="PAUSE"
            }
            "SKILLS" -> when {
                hit(52f,258f,370f,46f) -> {val cost=skillLevel*45;if(skillLevel<5&&gold>=cost){gold-=cost;skillLevel++;save();say("Arc Slash naik ke Lv."+skillLevel)}else say("Gold kurang atau level maksimum.")}
                hit(52f,382f,190f,44f) -> screen="PAUSE"
            }
            "EQUIPMENT" -> when {
                hit(52f,183f,370f,44f) -> {val cost=(weaponLevel+1)*55;if(weaponLevel<5&&gold>=cost){gold-=cost;weaponLevel++;save();say("Senjata ditingkatkan.")}else say("Gold kurang atau level maksimum.")}
                hit(52f,281f,370f,44f) -> {val cost=(armorLevel+1)*50;if(armorLevel<5&&gold>=cost){gold-=cost;armorLevel++;save();say("Armor ditingkatkan.")}else say("Gold kurang atau level maksimum.")}
                hit(52f,382f,190f,44f) -> {screen=equipmentReturnScreen;moveX=0f;moveY=0f}
            }
            "QUESTS" -> if(hit(52f,382f,190f,44f))screen="PAUSE"
            "SETTINGS" -> when {
                hit(52f,236f,320f,44f) -> showExportDialog()
                hit(52f,290f,320f,44f) -> showImportDialog()
                hit(52f,360f,320f,46f) -> screen="MAIN"
            }
            "PAUSE" -> when {
                hit(52f,150f,370f,44f)->screen="GAME"
                hit(52f,208f,180f,44f)->screen="CHARACTER"
                hit(242f,208f,180f,44f)->screen="GEMMA"
                hit(52f,266f,180f,44f)->screen="INVENTORY"
                hit(52f,324f,180f,44f)->screen="SKILLS"
                hit(242f,324f,180f,44f)->{equipmentReturnScreen="PAUSE";screen="EQUIPMENT"}
                hit(52f,382f,180f,44f)->screen="CAMPAIGN"
                hit(242f,382f,180f,44f)->screen="MAIN"
            }
        }
        save();invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN){val tx=e.getX()*960f/width;val ty=e.getY()*540f/height;if(screen=="CITY"){if(tx<170f&&ty>370f){stickId=e.getPointerId(e.actionIndex);setStick(tx,ty)}else clickMenu(tx,ty);invalidate();return true};if(screen!="GAME"){clickMenu(tx,ty);return true};if(tx>880f&&ty in 50f..90f){screen="PAUSE";invalidate();return true}}
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i=e.actionIndex; val tx=e.getX(i)*960f/width; val ty=e.getY(i)*540f/height
                if(tx<170f && ty>370f){stickId=e.getPointerId(i);setStick(tx,ty)}
                else if(ty in 10f..52f && tx>=550f){
                    val idx=((tx-554f)/96f).toInt().coerceIn(0,3)
                    command=listOf("FOLLOW","ATTACK","GUARD","RANGE")[idx];say("Gemma menerima perintah $command.")
                } else if(tx in 630f..730f && ty>390f){ performPlayerAttack() }
                 else if(tx in 735f..825f && ty>390f && dodgeCooldown<=0f){
                    dodgeTime=0.22f;dodgeCooldown=3f;invuln=0.28f;say("Dodge!",0.7f)
                } else if(tx>825f && ty>390f && skillCooldown<=0f){
                    if(mana<25f) say("Mana belum cukup untuk Arc Slash.",1f)
                    else {
                        mana-=25f; skillCooldown=6f; screenShake=max(screenShake,0.20f); hitFlash=0.12f
                        val targets=mobs.filter{it.alive && dist(it.x,it.y,x,y)<132f}.toList()
                        fx(x+12f,y-8f,x+12f,y-8f,5,0.42f); fx(x+5f,y-12f,x+5f,y-12f,7,0.40f); fx(x,y,x,y,6,0.25f)
                        targets.forEach { m ->
                            m.hp-=42f+(skillLevel-1)*18f+weaponLevel*5f
                             m.hitEmoji = "⚡"; m.hitEmojiTime = 0.85f
                            fx(m.x,m.y,m.x,m.y,5,0.42f); fx(m.x,m.y,m.x,m.y,2,0.30f); fx(m.x,m.y,m.x,m.y,6,0.26f)
                            if(m.hp<=0f)defeat(m)
                        }
                        sfx("spell",ToneGenerator.TONE_PROP_BEEP)
                        say(if(targets.isEmpty())"ARC SLASH! Tidak ada musuh dalam jangkauan." else "ARC SLASH! "+targets.size+" musuh terkena.",1.2f)
                    }
                };return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(stickId>=0) for(i in 0 until e.pointerCount) if(e.getPointerId(i)==stickId) setStick(e.getX(i)*960f/width,e.getY(i)*540f/height)
                return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP,MotionEvent.ACTION_CANCEL -> {
                if(e.actionMasked==MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex.coerceIn(0,e.pointerCount-1))==stickId){stickId=-1;moveX=0f;moveY=0f}
                return true
            }
        };return true
    }
    private fun setStick(tx: Float,ty: Float) {
        val dx=tx-88f;val dy=ty-445f;val len=sqrt(dx*dx+dy*dy).coerceAtLeast(1f)
        moveX=(dx/len).coerceIn(-1f,1f);moveY=(dy/len).coerceIn(-1f,1f)
        if(kotlin.math.abs(dx)<12f)moveX=0f
        if(kotlin.math.abs(dy)<12f)moveY=0f
    }
}
