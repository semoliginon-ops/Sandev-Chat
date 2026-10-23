package com.sandev.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Native turn-based RPG screen. It lives inside Sandev Chat and calls the existing
 * local inference engine through askGemma; it does not create a second model/runtime.
 */
class SandevRpgGameView(
    context: Context,
    private val scope: CoroutineScope,
    private val aiReady: () -> Boolean,
    private val askGemma: suspend (String) -> String,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private data class Hero(
        var level: Int = 1,
        var xp: Int = 0,
        var hp: Int = 120,
        var maxHp: Int = 120,
        var mp: Int = 45,
        var maxMp: Int = 45,
        var atk: Int = 17,
        var def: Int = 9,
        var potions: Int = 3
    )

    private data class Enemy(
        val name: String,
        val emoji: String,
        val kind: String,
        val level: Int,
        var hp: Int,
        val maxHp: Int,
        val atk: Int,
        val def: Int,
        val xp: Int,
        val boss: Boolean = false
    )

    private data class FoeType(
        val name: String, val emoji: String, val kind: String,
        val hp: Double, val atk: Double, val def: Double, val xp: Double
    )

    private val prefs = context.getSharedPreferences("sandev_rpg_save_v1", Context.MODE_PRIVATE)
    private val player = Hero()
    private val gemma = Hero(level = 1, hp = 112, maxHp = 112, mp = 50, maxMp = 50, atk = 16, def = 10, potions = 2)
    private var unlocked = 1
    private var cleared = 0
    private var coins = 0
    private var smallPotions = 3
    private var largePotions = 0
    private var ethers = 1
    private var ore = 0
    private var edgeLevel = 0
    private var guardLevel = 0
    private var focusLevel = 0
    private var stage = 1
    private var screen = "menu"
    private var enemies = mutableListOf<Enemy>()
    private var selectedTarget = 0
    private var turn = 1
    private var busy = false
    private var playerGuarding = false
    private var gemmaGuarding = false
    private var bubble = ""
    private var bubbleJob: Job? = null
    private var decisionJob: Job? = null
    private var battleLog = mutableListOf<String>()
    private var lastResult = ""
    private var lastReward = ""

    private val foeTypes = listOf(
        FoeType("Grimfang", "👹", "brute", 1.30, 1.12, 1.0, 1.0),
        FoeType("Venom Widow", "🕷️", "poison", 0.88, 1.08, 0.8, 1.05),
        FoeType("Ash Wraith", "👻", "drain", 0.92, 1.12, 0.9, 1.1),
        FoeType("Ironhide", "🦏", "guard", 1.50, 0.95, 1.5, 1.05),
        FoeType("Cinder Drake", "🐉", "burn", 1.24, 1.28, 1.0, 1.2),
        FoeType("Mire Stalker", "🐊", "bleed", 1.12, 1.18, 0.95, 1.1),
        FoeType("Hex Shaman", "🧙", "hex", 0.82, 1.14, 0.85, 1.15),
        FoeType("Bone Reaver", "💀", "heavy", 1.08, 1.40, 0.85, 1.15),
        FoeType("Storm Talon", "🦅", "swift", 0.86, 1.32, 0.75, 1.1),
        FoeType("Dread Golem", "🗿", "guard", 1.72, 1.0, 1.55, 1.25)
    )

    init {
        orientation = VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(10))
        setBackgroundColor(Color.rgb(12, 18, 32))
        loadSave()
        render()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 14f, color: Int = Color.rgb(235, 241, 255), bold: Boolean = false): TextView {
        return TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
    }

    private fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.rgb(24, 33, 52))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.rgb(54, 69, 95))
        }
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(9)
        layoutParams = params
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionButton(label: String, enabled: Boolean = true, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            isEnabled = enabled
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = GradientDrawable().apply {
                setColor(if (enabled) Color.rgb(66, 76, 112) else Color.rgb(45, 49, 62))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { if (isEnabled) action() }
        }
    }

    private fun addButtonRow(labels: List<Pair<String, () -> Unit>>) {
        val line = row()
        labels.forEach { item ->
            val button = actionButton(item.first, !busy, item.second)
            line.addView(button, LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        }
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
    }

    private fun heading(title: String, subtitle: String? = null) {
        addView(text("⚔️ SANDEV RPG", 20f, Color.rgb(220, 205, 255), true))
        addView(text(title, 16f, Color.WHITE, true))
        if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, Color.rgb(166, 181, 207)))
        addButtonRow(listOf("← Kembali ke Sandev Chat" to ::closeGame))
    }

    private fun closeGame() {
        decisionJob?.cancel()
        decisionJob = null
        bubbleJob?.cancel()
        bubbleJob = null
        busy = false
        onClose()
    }

    private fun render() {
        removeAllViews()
        when (screen) {
            "stages" -> renderStages()
            "bag" -> renderBag()
            "character" -> renderCharacter()
            "upgrade" -> renderUpgrade()
            "battle" -> renderBattle()
            "victory" -> renderResult(true)
            "defeat" -> renderResult(false)
            else -> renderMenu()
        }
        save()
    }

    private fun renderMenu() {
        heading("Petualangan Duo", "50 stage · Level maksimum 200 · Progress tersimpan di perangkat")
        addView(panel().apply {
            addView(text("🦹 Player  · Lv." + player.level, 16f, Color.WHITE, true))
            addView(text("HP " + player.hp + "/" + player.maxHp + "   MP " + player.mp + "/" + player.maxMp))
            addView(text("ATK " + player.atk + "   DEF " + player.def + "   XP " + player.xp + "/" + xpNeed(player.level), 12f, Color.rgb(166, 181, 207)))
            addView(text("🥷 Gemma  · Lv." + gemma.level, 16f, Color.WHITE, true))
            addView(text("HP " + gemma.hp + "/" + gemma.maxHp + "   MP " + gemma.mp + "/" + gemma.maxMp))
            addView(text("ATK " + gemma.atk + "   DEF " + gemma.def + "   XP " + gemma.xp + "/" + xpNeed(gemma.level), 12f, Color.rgb(166, 181, 207)))
        })
        addButtonRow(listOf("🗡️ Pilih Stage" to { screen = "stages"; render() }, "🎒 Bag" to { screen = "bag"; render() }))
        addButtonRow(listOf("🧑 Character" to { screen = "character"; render() }, "⬆️ Upgrade" to { screen = "upgrade"; render() }))
        addView(panel().apply {
            addView(text("PROGRESS", 12f, Color.rgb(110, 231, 183), true))
            addView(text("Stage terbuka: " + unlocked + "/50 · Selesai: " + cleared + "/50"))
            addView(text("Koin: " + coins + " · Potion: " + smallPotions + " kecil / " + largePotions + " besar · Ether: " + ethers))
            addView(text("Gemma menggunakan model lokal untuk memilih aksi battle saat model tersedia. Jika output kosong, giliran tetap berjalan dengan aksi cadangan.", 12f, Color.rgb(166, 181, 207)))
        })
        addButtonRow(listOf("Mulai Stage " + unlocked to { startBattle(unlocked) }))
    }

    private fun renderStages() {
        heading("Pilih Stage", "Stage yang terkunci terbuka setelah stage sebelumnya dituntaskan.")
        addView(panel().apply {
            addView(text("Jumlah musuh: 1–10 = 2 · 11–20 = 3 · 21–30 = 4 · 31–40 = 5 · 41–49 = 6 · Stage 50 = boss final.", 12f, Color.rgb(166, 181, 207)))
        })
        for (rangeStart in 1..50 step 5) {
            val line = row()
            for (n in rangeStart..min(50, rangeStart + 4)) {
                val label = if (n == 50) "👑 50" else n.toString() + if (n <= cleared) " ✓" else ""
                val button = actionButton(label, n <= unlocked) { startBattle(n) }
                line.addView(button, LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4); bottomMargin = dp(4) })
            }
            addView(line)
        }
    }

    private fun renderBag() {
        heading("Bag / Inventory", "Gunakan item di luar battle untuk memulihkan tim.")
        addView(panel().apply {
            addView(text("🧪 Small Potion × " + smallPotions + "  — pulihkan 35% HP"))
            addView(text("❤️ Large Potion × " + largePotions + "  — pulihkan 70% HP"))
            addView(text("🔷 Ether × " + ethers + "  — pulihkan 30% MP"))
            addView(text("⛏️ Upgrade Ore × " + ore))
        })
        addButtonRow(listOf("Small Potion" to { useItemOutside("small") }, "Large Potion" to { useItemOutside("large") }))
        addButtonRow(listOf("Ether" to { useItemOutside("ether") }, "Menu" to { screen = "menu"; render() }))
    }

    private fun renderCharacter() {
        heading("Character & Equipment", "Statistik meningkat saat naik level dan upgrade.")
        addHeroPanel("🦹 Player", player)
        addHeroPanel("🥷 Gemma", gemma)
        addView(panel().apply {
            addView(text("Equipment", 15f, Color.WHITE, true))
            addView(text("Weapon tier " + edgeLevel + " · Armor tier " + guardLevel + " · Focus tier " + focusLevel))
            addView(text("Upgrade meningkatkan serangan, pertahanan, HP dan MP maksimum.", 12f, Color.rgb(166, 181, 207)))
        })
        addButtonRow(listOf("Upgrade" to { screen = "upgrade"; render() }, "Menu" to { screen = "menu"; render() }))
    }

    private fun renderUpgrade() {
        heading("Upgrade", "Koin didapat dari kemenangan; pilih peningkatan sesuai kebutuhan.")
        addView(panel().apply {
            addView(text("Koin: " + coins + " · Ore: " + ore, 15f, Color.rgb(110, 231, 183), true))
            addView(text("Edge Lv." + edgeLevel + " — +4 ATK Player · Biaya " + (100 + edgeLevel * 130)))
            addView(text("Guard Lv." + guardLevel + " — +3 DEF dan +5% HP maksimum · Biaya " + (100 + guardLevel * 130)))
            addView(text("Focus Lv." + focusLevel + " — +5 MP maksimum dan +2 ATK Gemma · Biaya " + (150 + focusLevel * 160)))
        })
        addButtonRow(listOf("⚔️ Edge" to { buyUpgrade("edge") }, "🛡️ Guard" to { buyUpgrade("guard") }))
        addButtonRow(listOf("✨ Focus" to { buyUpgrade("focus") }, "Menu" to { screen = "menu"; render() }))
    }

    private fun addHeroPanel(label: String, hero: Hero) {
        addView(panel().apply {
            addView(text(label + " · Level " + hero.level, 16f, Color.WHITE, true))
            addView(text("HP " + hero.hp + "/" + hero.maxHp + " · MP " + hero.mp + "/" + hero.maxMp))
            addView(text("ATK " + hero.atk + " · DEF " + hero.def + " · XP " + hero.xp + "/" + xpNeed(hero.level)))
            addView(text("Potion pribadi: " + hero.potions))
        })
    }

    private fun renderBattle() {
        heading(if (stage == 50) "Stage 50 · FINAL BOSS" else "Stage " + stage, "Turn " + turn + if (busy) " · Gemma sedang berpikir…" else " · Giliran tim")
        addHeroPanel("🦹 Player", player)
        addHeroPanel("🥷 Gemma", gemma)
        addView(panel().apply {
            addView(text("MUSUH — pilih target", 13f, Color.rgb(251, 191, 36), true))
            enemies.forEachIndexed { index, enemy ->
                val label = if (enemy.hp <= 0) "☠️ " + enemy.name + " — Kalah" else
                    (if (index == selectedTarget) "👉 " else "") + enemy.emoji + " " + enemy.name + " · Lv." + enemy.level + "\nHP " + enemy.hp + "/" + enemy.maxHp + " · ATK " + enemy.atk + " · DEF " + enemy.def
                val button = actionButton(label, enemy.hp > 0 && !busy) { selectedTarget = index; render() }
                button.gravity = Gravity.START
                addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
            }
        })
        if (bubble.isNotBlank()) addView(panel().apply { addView(text("🥷 Gemma: " + bubble, 13f, Color.rgb(196, 181, 253), true)) })
        addButtonRow(listOf("⚔️ Attack" to { playerAction("ATTACK") }, "✨ Skill" to { playerAction("SKILL") }))
        addButtonRow(listOf("🛡️ Defend" to { playerAction("DEFEND") }, "🧪 Potion" to { playerAction("POTION") }))
        addButtonRow(listOf("🎒 Item" to { screen = "bag"; render() }, "Menu" to { screen = "menu"; render() }))
        addView(panel().apply {
            addView(text("BATTLE LOG", 11f, Color.rgb(110, 231, 183), true))
            battleLog.takeLast(8).asReversed().forEach { addView(text(it, 12f, Color.rgb(194, 205, 223))) }
        })
        if (busy) addView(text("Menunggu keputusan Gemma… jika model mengembalikan output kosong atau timeout, aksi cadangan akan menyelesaikan giliran.", 12f, Color.rgb(166, 181, 207)))
    }

    private fun renderResult(win: Boolean) {
        heading(if (win) "Kemenangan!" else "Tim tumbang", lastResult)
        addView(panel().apply {
            addView(text(if (win) "🏆 Stage berhasil diselesaikan!" else "💫 Tim butuh pemulihan sebelum mencoba lagi.", 18f, Color.WHITE, true))
            if (lastReward.isNotBlank()) addView(text(lastReward, 14f, Color.rgb(110, 231, 183), true))
            addView(text("Progress tersimpan otomatis.", 12f, Color.rgb(166, 181, 207)))
        })
        addButtonRow(listOf((if (win) "Stage berikutnya" else "Coba lagi") to {
            if (win) startBattle(min(50, max(stage + 1, unlocked))) else startBattle(stage)
        }, "Menu" to { screen = "menu"; render() }))
    }

    private fun startBattle(number: Int) {
        if (number > unlocked) return
        stage = number
        turn = 1
        busy = false
        playerGuarding = false
        gemmaGuarding = false
        selectedTarget = 0
        bubble = ""
        battleLog.clear()
        val count = enemyCount(number)
        enemies = MutableList(count) { index -> makeEnemy(number, index) }
        screen = "battle"
        say(if (number == 50) "Kita hadapi boss terakhir bersama!" else "Aku siap. Fokus ke target yang paling berbahaya!")
        addLog("Stage " + number + " dimulai. Musuh: " + count + ".")
        render()
    }

    private fun enemyCount(number: Int): Int = when {
        number == 50 -> 1
        number <= 10 -> 2
        number <= 20 -> 3
        number <= 30 -> 4
        number <= 40 -> 5
        else -> 6
    }

    private fun makeEnemy(number: Int, index: Int): Enemy {
        if (number == 50) return Enemy("Azhurak, World-Ender", "🐲", "boss", 200, 11000, 11000, 300, 90, 8000, true)
        val type = foeTypes[(number * 3 + index * 2 - 3).mod(foeTypes.size)]
        val level = min(199, max(1, number * 4 - 2 + index))
        val hp = max(35, ((55 + number * 5.5 + level * 4.0) * type.hp).toInt())
        val attack = max(7, ((10 + number * 1.8 + level * 0.48) * type.atk).toInt())
        val defense = max(1, ((2 + number * 1.4 + level * 0.34) * type.def).toInt())
        val xpReward = ((55 + number * 13) * type.xp).toInt()
        return Enemy(type.name, type.emoji, type.kind, level, hp, hp, attack, defense, xpReward)
    }

    private fun playerAction(action: String) {
        if (screen != "battle" || busy) return
        val target = enemies.getOrNull(selectedTarget)
        when (action) {
            "ATTACK" -> {
                if (target == null || target.hp <= 0) { say("Pilih musuh yang masih berdiri dulu."); render(); return }
                val damage = max(1, player.atk + Random.nextInt(0, 7) - target.def / 2)
                target.hp = max(0, target.hp - damage)
                addLog("🦹 menyerang " + target.name + " sebesar " + damage + " damage.")
                say("Serangan bagus! " + target.name + " terkena " + damage + " damage.")
            }
            "SKILL" -> {
                if (player.mp < 12) { say("MP-ku kurang untuk skill. Coba attack atau potion."); render(); return }
                if (target == null || target.hp <= 0) { say("Pilih target skill dulu."); render(); return }
                player.mp -= 12
                val damage = max(1, player.atk * 2 + Random.nextInt(4, 13) - target.def / 3)
                target.hp = max(0, target.hp - damage)
                addLog("✨ Skill Player mengenai " + target.name + " sebesar " + damage + " damage.")
                say("Skill! Rasakan ini, " + target.name + "!")
            }
            "DEFEND" -> {
                playerGuarding = true
                player.mp = min(player.maxMp, player.mp + 5)
                addLog("🦹 bersiap bertahan.")
                say("Aku lindungi tim! MP sedikit pulih.")
            }
            "POTION" -> {
                if (smallPotions <= 0 && largePotions <= 0) { say("Potion habis. Kita perlu beli atau cari hadiah stage."); render(); return }
                if (player.hp * 100 / player.maxHp < 70 && largePotions > 0) {
                    largePotions--; player.hp = min(player.maxHp, player.hp + player.maxHp * 70 / 100)
                    addLog("🧪 Player memakai Large Potion.")
                } else if (smallPotions > 0) {
                    smallPotions--; player.hp = min(player.maxHp, player.hp + player.maxHp * 35 / 100)
                    addLog("🧪 Player memakai Small Potion.")
                } else {
                    largePotions--; player.hp = min(player.maxHp, player.hp + player.maxHp * 70 / 100)
                    addLog("🧪 Player memakai Large Potion.")
                }
                say("Sedikit pemulihan, lalu kita lanjut!")
            }
        }
        if (checkBattleEnd()) return
        selectedTarget = enemies.indexOfFirst { it.hp > 0 }.coerceAtLeast(0)
        askGemmaTurn()
    }

    private fun askGemmaTurn() {
        busy = true
        render()
        val prompt = buildString {
            appendLine("Kamu mengendalikan partner Gemma dalam RPG turn-based. Pilih aksi legal paling berguna berdasarkan status saat ini.")
            appendLine("PLAYER: HP " + player.hp + "/" + player.maxHp + ", MP " + player.mp + "/" + player.maxMp + ", ATK " + player.atk + ", potion " + (smallPotions + largePotions) + ".")
            appendLine("GEMMA: HP " + gemma.hp + "/" + gemma.maxHp + ", MP " + gemma.mp + "/" + gemma.maxMp + ", ATK " + gemma.atk + ", potion " + gemma.potions + ".")
            appendLine("MUSUH HIDUP: " + enemies.mapIndexedNotNull { index, enemy -> if (enemy.hp > 0) "$index:${enemy.name} HP${enemy.hp}/${enemy.maxHp} ATK${enemy.atk} DEF${enemy.def} ${enemy.kind}" else null }.joinToString("; "))
            appendLine("SKILL perlu 12 MP; POTION hanya jika stok ada dan HP perlu dipulihkan; DEFEND mengurangi damage musuh.")
            appendLine("Balas tepat 3 baris, tanpa markdown atau penjelasan:")
            appendLine("ACTION: ATTACK / SKILL / DEFEND / POTION")
            appendLine("TARGET: indeks musuh hidup (0-based)")
            appendLine("COMMENT: satu komentar pendek dalam bahasa Indonesia")
        }
        decisionJob = scope.launch {
            var output = ""
            var modelWasReady = false
            try {
                modelWasReady = aiReady()
                if (modelWasReady) output = askGemma(prompt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                output = ""
            }

            // Small local models sometimes answer in free text or return an empty first generation.
            // Retry once with a shorter, action-first instruction when the first response is unusable.
            var parsedAction = GameDecisionProtocol.parseAction(output, listOf("ATTACK", "SKILL", "DEFEND", "POTION"))
            if (modelWasReady && parsedAction == null) {
                try {
                    val retryPrompt = "Pilih aksi untuk state RPG yang baru saja diberikan. Jangan ulangi penjelasan. Balas tepat 3 baris tanpa markdown:\n" +
                        "ACTION: ATTACK / SKILL / DEFEND / POTION\nTARGET: indeks musuh hidup (0-based)\nCOMMENT: satu komentar pendek bahasa Indonesia."
                    val retryOutput = askGemma(retryPrompt)
                    if (retryOutput.isNotBlank()) output = retryOutput
                    parsedAction = GameDecisionProtocol.parseAction(output, listOf("ATTACK", "SKILL", "DEFEND", "POTION"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The fallback below keeps the battle moving if the model fails for a non-timeout reason.
                }
            }

            withContext(Dispatchers.Main) {
                if (screen != "battle") { busy = false; save(); return@withContext }
                val targetMatch = Regex("(?im)^\\s*TARGET\\s*:\\s*(\\d+)").find(output)
                val commentMatch = Regex("(?im)^\\s*COMMENT\\s*:\\s*(.+)$").find(output)
                val parsedTarget = targetMatch?.groupValues?.get(1)?.toIntOrNull()
                val action = if (parsedAction != null && isGemmaActionLegal(parsedAction)) parsedAction else fallbackGemmaAction()
                val targetIndex = if (parsedTarget != null && (enemies.getOrNull(parsedTarget)?.hp ?: 0) > 0) parsedTarget else bestTarget()
                val comment = commentMatch?.groupValues?.get(1)?.trim().orEmpty()
                if (parsedAction == null || !isGemmaActionLegal(parsedAction)) {
                    say(when {
                        !modelWasReady -> "Model Gemma belum siap, jadi sistem mengambil giliran ini."
                        else -> "Gemma belum memberi aksi yang bisa dibaca setelah dicoba ulang; sistem mengambil giliran ini."
                    })
                    addLog("Aksi sistem (fallback); respons model tidak valid atau kosong.")
                } else if (comment.isNotBlank()) {
                    say(comment.take(180))
                } else {
                    say("Aku sudah memilih langkah. Kita lanjutkan bersama!")
                }
                applyGemmaAction(action, targetIndex)
                busy = false
                if (!checkBattleEnd()) {
                    enemyTurn()
                    turn++
                    checkBattleEnd()
                }
                render()
                save()
            }
        }
    }

    /**
     * Deterministic fallback shared with other game adapters through GameBrain.
     * LLM suggestions are advisory; the selected action is validated separately.
     */
    private fun fallbackGemmaAction(): String {
        val alive = enemies.filter { it.hp > 0 }
        if (alive.isEmpty()) return "DEFEND"
        val teamHealth = min(
            player.hp.toDouble() / player.maxHp.coerceAtLeast(1),
            gemma.hp.toDouble() / gemma.maxHp.coerceAtLeast(1)
        ).coerceIn(0.0, 1.0)
        val resourceRatio = (gemma.mp.toDouble() / gemma.maxMp.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val threat = (alive.maxOfOrNull { it.atk }.orZero().toDouble() /
            gemma.maxHp.coerceAtLeast(1).toDouble() * 2.0).coerceIn(0.0, 1.0)
        val canHealGemma = gemma.potions > 0 && gemma.hp < gemma.maxHp * 85 / 100
        val canHealPlayer = smallPotions + largePotions > 0 && player.hp < player.maxHp * 80 / 100
        val chosen = GameBrain.choose(
            GameBrain.Situation(
                gameType = "turn-based-rpg",
                healthRatio = teamHealth,
                resourceRatio = resourceRatio,
                threat = threat,
                objectiveUrgency = (0.55 + stage / 100.0).coerceAtMost(1.0)
            ),
            listOf(
                GameBrain.Candidate("ATTACK", legal = true, progress = 0.68, survival = 0.12, risk = 0.12),
                GameBrain.Candidate("SKILL", legal = gemma.mp >= 12, progress = 1.0, survival = 0.12, resourceCost = 0.48, risk = 0.12),
                GameBrain.Candidate("DEFEND", legal = true, progress = 0.08, survival = 0.72),
                GameBrain.Candidate(
                    "POTION",
                    legal = canHealGemma || canHealPlayer,
                    progress = 0.08,
                    survival = if (canHealGemma && gemma.hp * 100 / gemma.maxHp < 35 ||
                        canHealPlayer && player.hp * 100 / player.maxHp < 30) 1.0 else 0.72,
                    resourceCost = 0.08
                )
            )
        )
        return chosen?.actionId ?: "ATTACK"
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun isGemmaActionLegal(action: String): Boolean {
        val aliveEnemies = enemies.any { it.hp > 0 }
        return when (action) {
            "ATTACK" -> aliveEnemies
            "SKILL" -> gemma.mp >= 12 && aliveEnemies
            "DEFEND" -> true
            "POTION" -> (gemma.potions > 0 && gemma.hp < gemma.maxHp * 85 / 100) ||
                (smallPotions + largePotions > 0 && player.hp < player.maxHp * 80 / 100)
            else -> false
        }
    }

    private fun bestTarget(): Int {
        val alive = enemies.indices.filter { enemies[it].hp > 0 }
        return alive.minByOrNull { enemies[it].hp } ?: 0
    }

    private fun applyGemmaAction(action: String, targetIndex: Int) {
        val target = enemies.getOrNull(targetIndex)
        when (action) {
            "SKILL" -> {
                if (gemma.mp < 12 || target == null || target.hp <= 0) { applyGemmaAction("ATTACK", bestTarget()); return }
                gemma.mp -= 12
                val damage = max(1, gemma.atk * 2 + Random.nextInt(4, 12) - target.def / 3)
                target.hp = max(0, target.hp - damage)
                addLog("🥷 Gemma memakai skill pada " + target.name + " (" + damage + " damage).")
            }
            "DEFEND" -> {
                gemmaGuarding = true
                gemma.mp = min(gemma.maxMp, gemma.mp + 5)
                addLog("🥷 Gemma bertahan melindungi tim.")
            }
            "POTION" -> {
                if (gemma.potions > 0 && gemma.hp < gemma.maxHp * 85 / 100) {
                    gemma.potions--
                    gemma.hp = min(gemma.maxHp, gemma.hp + gemma.maxHp * 40 / 100)
                    addLog("🥷 Gemma memakai potion dan memulihkan HP.")
                } else if (smallPotions + largePotions > 0 && player.hp < player.maxHp * 80 / 100) {
                    if (largePotions > 0 && (smallPotions == 0 || player.hp < player.maxHp / 2)) {
                        largePotions--
                        player.hp = min(player.maxHp, player.hp + player.maxHp * 70 / 100)
                    } else if (smallPotions > 0) {
                        smallPotions--
                        player.hp = min(player.maxHp, player.hp + player.maxHp * 35 / 100)
                    } else {
                        applyGemmaAction("ATTACK", bestTarget())
                        return
                    }
                    addLog("🥷 Gemma menggunakan potion untuk menolong Player.")
                } else applyGemmaAction("ATTACK", bestTarget())
            }
            else -> {
                if (target == null || target.hp <= 0) { applyGemmaAction("ATTACK", bestTarget()); return }
                val damage = max(1, gemma.atk + Random.nextInt(0, 7) - target.def / 2)
                target.hp = max(0, target.hp - damage)
                addLog("🥷 Gemma menyerang " + target.name + " (" + damage + " damage).")
            }
        }
        if (target != null && target.hp <= 0) addLog(target.emoji + " " + target.name + " tumbang!")
    }

    private fun enemyTurn() {
        enemies.filter { it.hp > 0 }.forEach { enemy ->
            if (player.hp <= 0 && gemma.hp <= 0) return
            val targetGemma = when {
                player.hp <= 0 -> true
                gemma.hp <= 0 -> false
                else -> Random.nextInt(100) < 38
            }
            val target = if (targetGemma) gemma else player
            val guarding = if (targetGemma) gemmaGuarding else playerGuarding
            var damage = max(1, enemy.atk + Random.nextInt(0, 5) - target.def / 2)
            if (guarding) damage = max(1, damage / 2)
            if (enemy.boss && Random.nextInt(100) < 28) damage = (damage * 1.5).toInt()
            target.hp = max(0, target.hp - damage)
            addLog(enemy.emoji + " " + enemy.name + " menyerang " + (if (targetGemma) "Gemma" else "Player") + " (" + damage + " damage).")
            if (enemy.kind == "drain" && target.hp > 0) enemy.hp = min(enemy.maxHp, enemy.hp + damage / 2)
        }
        playerGuarding = false
        gemmaGuarding = false
        player.mp = min(player.maxMp, player.mp + 3)
        gemma.mp = min(gemma.maxMp, gemma.mp + 4)
    }

    private fun checkBattleEnd(): Boolean {
        if (enemies.all { it.hp <= 0 }) {
            val baseXp = if (stage == 50) 8000 else enemies.sumOf { it.xp }
            val rewardCoins = if (stage == 50) 5000 else 35 + stage * 28 + enemies.size * 18
            val playerLevels = gainXp(player, baseXp)
            val gemmaLevels = gainXp(gemma, baseXp)
            coins += rewardCoins
            ore += if (stage == 50) 15 else max(1, stage / 4)
            smallPotions += if (stage % 3 == 0 || stage == 50) 1 else 0
            if (stage % 7 == 0 || stage == 50) largePotions++
            cleared = max(cleared, stage)
            unlocked = max(unlocked, min(50, stage + 1))
            lastResult = "Stage " + stage + " selesai."
            lastReward = "XP +" + baseXp + " untuk Player dan Gemma · Koin +" + rewardCoins +
                " · Level naik Player +" + playerLevels + " / Gemma +" + gemmaLevels
            addLog("Menang! " + lastReward)
            screen = "victory"
            busy = false
            save()
            return true
        }
        if (player.hp <= 0 && gemma.hp <= 0) {
            player.hp = max(1, player.maxHp / 2)
            gemma.hp = max(1, gemma.maxHp / 2)
            player.mp = max(0, player.maxMp / 2)
            gemma.mp = max(0, gemma.maxMp / 2)
            lastResult = "Stage " + stage + " belum berhasil. HP tim dipulihkan setengah."
            lastReward = ""
            screen = "defeat"
            busy = false
            save()
            return true
        }
        return false
    }

    private fun gainXp(hero: Hero, amount: Int): Int {
        hero.xp += max(0, amount)
        var levels = 0
        while (hero.level < 200 && hero.xp >= xpNeed(hero.level)) {
            hero.xp -= xpNeed(hero.level)
            hero.level++
            levels++
            hero.maxHp += 7
            hero.maxMp += 3
            hero.atk += 2
            hero.def += 1
            hero.hp = hero.maxHp
            hero.mp = hero.maxMp
        }
        if (hero.level >= 200) hero.xp = 0
        return levels
    }

    private fun xpNeed(level: Int): Int = 120 + level * 9

    private fun useItemOutside(item: String) {
        when (item) {
            "small" -> if (smallPotions > 0 && (player.hp < player.maxHp || gemma.hp < gemma.maxHp)) {
                smallPotions--; player.hp = min(player.maxHp, player.hp + player.maxHp * 35 / 100)
                gemma.hp = min(gemma.maxHp, gemma.hp + gemma.maxHp * 35 / 100)
            } else say("Potion kecil habis atau HP tim sudah penuh.")
            "large" -> if (largePotions > 0 && (player.hp < player.maxHp || gemma.hp < gemma.maxHp)) {
                largePotions--; player.hp = min(player.maxHp, player.hp + player.maxHp * 70 / 100)
                gemma.hp = min(gemma.maxHp, gemma.hp + gemma.maxHp * 70 / 100)
            } else say("Potion besar habis atau HP tim sudah penuh.")
            "ether" -> if (ethers > 0 && (player.mp < player.maxMp || gemma.mp < gemma.maxMp)) {
                ethers--; player.mp = min(player.maxMp, player.mp + player.maxMp * 30 / 100)
                gemma.mp = min(gemma.maxMp, gemma.mp + gemma.maxMp * 30 / 100)
            } else say("Ether habis atau MP tim sudah penuh.")
        }
        render()
    }

    private fun buyUpgrade(which: String) {
        val cost = when (which) { "edge" -> 100 + edgeLevel * 130; "guard" -> 100 + guardLevel * 130; else -> 150 + focusLevel * 160 }
        if (coins < cost) { say("Koin belum cukup. Menangkan stage untuk mengumpulkan koin."); render(); return }
        coins -= cost
        when (which) {
            "edge" -> { edgeLevel++; player.atk += 4 }
            "guard" -> { guardLevel++; player.def += 3; player.maxHp = (player.maxHp * 1.05).toInt(); player.hp = min(player.maxHp, player.hp + 10) }
            else -> { focusLevel++; player.maxMp += 5; gemma.maxMp += 5; gemma.atk += 2 }
        }
        say("Upgrade berhasil! Statistik tim meningkat.")
        render()
    }

    private fun say(message: String) {
        bubble = message
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            kotlinx.coroutines.delay(3000L)
            withContext(Dispatchers.Main) { bubble = ""; if (screen == "battle") render() }
        }
    }

    private fun addLog(message: String) {
        battleLog.add(message)
        if (battleLog.size > 30) battleLog.removeAt(0)
    }

    private fun loadSave() {
        val raw = prefs.getString("state", null) ?: return
        try {
            val data = JSONObject(raw)
            fun loadHero(key: String, hero: Hero) {
                val o = data.optJSONObject(key) ?: return
                hero.level = o.optInt("level", hero.level); hero.xp = o.optInt("xp", hero.xp)
                hero.hp = o.optInt("hp", hero.hp); hero.maxHp = o.optInt("maxHp", hero.maxHp)
                hero.mp = o.optInt("mp", hero.mp); hero.maxMp = o.optInt("maxMp", hero.maxMp)
                hero.atk = o.optInt("atk", hero.atk); hero.def = o.optInt("def", hero.def)
                hero.potions = o.optInt("potions", hero.potions)
                hero.hp = hero.hp.coerceIn(0, hero.maxHp); hero.mp = hero.mp.coerceIn(0, hero.maxMp)
            }
            loadHero("player", player); loadHero("gemma", gemma)
            unlocked = data.optInt("unlocked", 1).coerceIn(1, 50)
            cleared = data.optInt("cleared", 0).coerceIn(0, 50)
            coins = data.optInt("coins", 0).coerceAtLeast(0)
            smallPotions = data.optInt("smallPotions", 3).coerceAtLeast(0)
            largePotions = data.optInt("largePotions", 0).coerceAtLeast(0)
            ethers = data.optInt("ethers", 1).coerceAtLeast(0)
            ore = data.optInt("ore", 0).coerceAtLeast(0)
            edgeLevel = data.optInt("edgeLevel", 0).coerceAtLeast(0)
            guardLevel = data.optInt("guardLevel", 0).coerceAtLeast(0)
            focusLevel = data.optInt("focusLevel", 0).coerceAtLeast(0)
        } catch (_: Exception) { }
    }

    private fun save() {
        fun heroJson(hero: Hero): JSONObject = JSONObject()
            .put("level", hero.level).put("xp", hero.xp).put("hp", hero.hp).put("maxHp", hero.maxHp)
            .put("mp", hero.mp).put("maxMp", hero.maxMp).put("atk", hero.atk).put("def", hero.def).put("potions", hero.potions)
        prefs.edit().putString("state", JSONObject()
            .put("player", heroJson(player)).put("gemma", heroJson(gemma))
            .put("unlocked", unlocked).put("cleared", cleared).put("coins", coins)
            .put("smallPotions", smallPotions).put("largePotions", largePotions).put("ethers", ethers)
            .put("ore", ore).put("edgeLevel", edgeLevel).put("guardLevel", guardLevel).put("focusLevel", focusLevel)
            .toString()).apply()
    }
}
