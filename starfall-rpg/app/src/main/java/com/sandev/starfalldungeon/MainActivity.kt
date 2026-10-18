package com.sandev.starfalldungeon

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.app.AlertDialog
import android.widget.EditText
import android.text.InputType
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
    var attack: Float = 1f, var windup: Float = 0f, var alive: Boolean = true
)

private class ForestGameView(context: Context) : View(context) {
    private val prefs = context.getSharedPreferences("starfall_rpg_save", Context.MODE_PRIVATE)
    private val paint = Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
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
    private var gemmaHp = 100f; private var gemmaMaxHp = 100f
    private var level = prefs.getInt("level", 1).coerceIn(1, 10)
    private var exp = prefs.getInt("exp", 0).coerceAtLeast(0)
    private var gold = prefs.getInt("gold", 0).coerceAtLeast(0)
    private var kills = prefs.getInt("kills", 0).coerceAtLeast(0)
    private var damage = 14f + (level - 1) * 2f
    private var attackTimer = 0f; private var gemmaTimer = 0f
    private var dodgeCooldown = 0f; private var dodgeTime = 0f; private var invuln = 0f
    private var skillCooldown = 0f; private var moveX = 0f; private var moveY = 0f
    private var stickId = -1; private var command = "FOLLOW"
    private var message = "Hutan Sunyi: berburu untuk EXP dan gold"
    private var messageTime = 5f; private var clock = 0f
    private var bossSpawned = false; private var bossCleared = false
    private var screen = "MAIN"
    private var cityMessage = "Kota Asterfall: bicara dengan warga sebelum masuk hutan."
    private var playerName = prefs.getString("player_name", "").orEmpty()
    private var hero = prefs.getString("hero", "Vanguard").orEmpty()
    private var potions = prefs.getInt("potions", 2).coerceAtLeast(0)
    private var materials = prefs.getInt("materials", 0).coerceAtLeast(0)
    private var weaponLevel = prefs.getInt("weapon_level", 0).coerceIn(0, 5)
    private var armorLevel = prefs.getInt("armor_level", 0).coerceIn(0, 5)
    private var skillLevel = prefs.getInt("skill_level", 1).coerceIn(1, 5)

    init { repeat(4) { spawn(if (it == 2) 1 else 0) } }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }
    private fun need(l: Int) = when (l) {
        1 -> 100; 2 -> 150; 3 -> 220; 4 -> 300; 5 -> 390
        6 -> 490; 7 -> 600; 8 -> 720; else -> 850
    }
    private fun say(s: String, t: Float = 2f) { message = s; messageTime = t }
    private fun sfx(kind: Int) { try { tone.startTone(kind, if (kind == ToneGenerator.TONE_PROP_BEEP) 150 else 210) } catch (_: Exception) {} }
    private fun fx(sx: Float, sy: Float, tx: Float, ty: Float, kind: Int, life: Float = 0.32f) { effects.add(Effect(sx,sy,tx,ty,life,kind)) }
    private fun save() {
        prefs.edit().putInt("level", level).putInt("exp", exp).putInt("gold", gold).putInt("kills", kills).putString("player_name", playerName).putString("hero", hero).putInt("potions", potions).putInt("materials", materials).putInt("weapon_level", weaponLevel).putInt("armor_level", armorLevel).putInt("skill_level", skillLevel).apply()
    }
    private fun blocked(ax: Float, ay: Float) =
        ax < 26f || ax > 934f || ay < 76f || ay > 450f || trees.any { it.contains(ax, ay) }

    private fun spawn(kind: Int) {
        var sx = 80f; var sy = 100f; var tries = 0
        do {
            sx = 70f + random.nextFloat() * 820f
            sy = 105f + random.nextFloat() * 320f
            tries++
        } while (tries < 15 && (dist(sx, sy, x, y) < 175f || trees.any { it.contains(sx, sy) }))
        val max = if (kind == 1) 85f else 50f
        mobs.add(Mob(sx, sy, kind, max, max, 1.2f + random.nextFloat()))
    }

    private fun update(dt: Float) {
        clock += dt
        hitFlash = max(0f, hitFlash-dt)
        effects.forEach { it.life -= dt }
        effects.removeAll { it.life <= 0f }
        messageTime = max(0f, messageTime - dt)
        attackTimer = max(0f, attackTimer - dt); gemmaTimer = max(0f, gemmaTimer - dt)
        dodgeCooldown = max(0f, dodgeCooldown - dt); dodgeTime = max(0f, dodgeTime - dt)
        skillCooldown = max(0f, skillCooldown - dt); invuln = max(0f, invuln - dt)

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
                    hitFlash=0.22f; fx(x,y,m.x,m.y,3); sfx(ToneGenerator.TONE_PROP_NACK)
                    say("Kena serangan! Baca tanda lalu dodge.", 1.7f)
                }
                m.attack = if (m.kind == 2) 4f else if (m.kind == 1) 3f else 2.5f
            }
        }

        if (attackTimer <= 0f) {
            val target = mobs.filter { it.alive && dist(it.x, it.y, x, y) < 112f }
                .minByOrNull { dist(it.x, it.y, x, y) }
            if (target != null) {
                target.hp -= damage + weaponLevel*5f; attackTimer = 0.7f
                fx(x,y,target.x,target.y,2); sfx(ToneGenerator.TONE_PROP_BEEP)
                if (target.hp <= 0f) defeat(target) else say("Serangan dasar mengenai target.", 0.65f)
            }
        }
        if (gemmaTimer <= 0f && command != "GUARD") {
            val target = mobs.filter { it.alive && dist(it.x, it.y, gemmaX, gemmaY) < 230f }
                .minByOrNull { dist(it.x, it.y, gemmaX, gemmaY) }
            if (target != null) {
                target.hp -= 10f + (level - 1) * 2f; gemmaTimer = 2f
                fx(gemmaX,gemmaY,target.x,target.y,1,0.52f); sfx(ToneGenerator.TONE_PROP_ACK)
                say("Gemma: ✨ Arcane Bolt!", 0.9f)
                if (target.hp <= 0f) defeat(target)
            }
        }
        if (hp <= 0f || gemmaHp <= 0f) {
            x = 470f; y = 285f; hp = maxHp; gemmaHp = gemmaMaxHp
            mobs.filter { it.alive && it.kind != 2 }.forEach { it.x = (it.x + 180f).coerceIn(55f, 900f) }
            say("Mundur ke tempat aman. Gold dan EXP tetap utuh.", 4f)
        }
        if (mobs.count { it.alive && it.kind != 2 } < 4 && random.nextFloat() < dt * 0.35f)
            spawn(if (random.nextFloat() < 0.25f) 1 else 0)
        if (!bossSpawned && level >= 5 && kills >= 18) {
            bossSpawned = true; mobs.add(Mob(810f, 390f, 2, 1200f, 1200f))
            say("THORNHEART GUARDIAN muncul! Serang saat ia selesai menghantam.", 5f)
        }
        if (bossSpawned && !bossCleared && mobs.none { it.alive && it.kind == 2 }) {
            bossCleared = true; exp += 250; gold += 125
            say("Bos tumbang! +250 EXP, +125 gold, material khusus.", 5f)
            levelUp(); save()
        }
    }

    private fun defeat(m: Mob) {
        if (!m.alive) return
        m.alive = false
        fx(m.x,m.y,m.x,m.y,4,0.55f); sfx(ToneGenerator.TONE_PROP_ACK)
        if (m.kind == 2) { kills++; return }
        kills++
        if (m.kind == 1) { exp += 22; gold += random.nextInt(6, 11) }
        else { exp += 12; gold += random.nextInt(3, 7) }
        val roll = random.nextInt(100)
        val drop = if (m.kind == 1 && roll < 60) { materials++; "material umum" }
            else if (m.kind == 0 && roll < 30) { materials++; "material umum" }
            else if (roll < 38) { potions++; "ramuan HP" } else ""
        say(if (drop.isEmpty()) "Monster tumbang! EXP dan gold didapat." else "Drop: $drop", 2f)
        levelUp(); save()
    }
    private fun levelUp() {
        while (level < 10 && exp >= need(level)) {
            exp -= need(level); level++; maxHp += 10f; gemmaMaxHp += 8f; damage += 2f
            hp = maxHp; gemmaHp = gemmaMaxHp
            say("LEVEL UP! Level $level. Perjuanganmu membuahkan hasil!", 3f)
        }
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
        if (screen == "GAME") { update(0.016f); drawForest(canvas); drawCharacters(canvas); drawEffects(canvas); drawHud(canvas) } else if (screen == "CITY") { updateCity(0.016f); drawCity(canvas) } else drawMenu(canvas)
        canvas.restore(); postInvalidateDelayed(16L)
    }
    private fun drawForest(c: Canvas) {
        c.drawColor(Color.rgb(24, 45, 36))
        paint.shader=LinearGradient(0f,0f,960f,540f,Color.rgb(28,65,48),Color.rgb(11,31,34),Shader.TileMode.CLAMP); c.drawRect(0f,0f,960f,540f,paint); paint.shader=null
        for (tx in 0..59) for (ty in 0..32) {
            val xx = tx * 16f; val yy = ty * 16f
            paint.color = if ((tx * 7 + ty * 13) % 5 == 0) Color.rgb(34,72,48) else Color.rgb(29,61,42)
            c.drawRect(xx, yy, xx + 16f, yy + 16f, paint)
            if ((tx * 3 + ty * 11) % 17 == 0) { paint.color = Color.rgb(63,98,55); c.drawRect(xx+5,yy+7,xx+8,yy+10,paint) }
        }
        trees.forEach { t ->
            paint.color = Color.argb(90,3,15,12); c.drawOval(RectF(t.left-9,t.bottom-3,t.right+12,t.bottom+15),paint)
            paint.color = Color.rgb(79,57,37); c.drawRoundRect(RectF(t.centerX()-8,t.centerY()-3,t.centerX()+9,t.bottom+10),4f,4f,paint)
            paint.shader=LinearGradient(t.left,t.top,t.right,t.bottom,Color.rgb(72,142,75),Color.rgb(18,63,43),Shader.TileMode.CLAMP); c.drawOval(RectF(t.left-7,t.top-7,t.right+7,t.bottom-3),paint); paint.shader=null
            paint.color = Color.rgb(56,126,61); c.drawOval(RectF(t.left+3,t.top+2,t.right-5,t.bottom-11),paint)
            paint.color = Color.rgb(103,170,80); c.drawCircle(t.left+16,t.top+15,5f,paint); c.drawCircle(t.left+29,t.top+24,4f,paint)
        }
        paint.color = Color.rgb(95,78,52); c.drawRect(430f,255f,520f,315f,paint)
        paint.color = Color.rgb(120,101,66); c.drawRect(442f,264f,508f,305f,paint)
    }
    private fun drawCharacters(c: Canvas) {
        mobs.filter { it.alive }.forEach { m ->
            if (m.kind == 2) {
                paint.color = Color.argb(100,0,0,0); c.drawOval(RectF(m.x-28,m.y+15,m.x+30,m.y+31),paint)
                paint.shader=LinearGradient(m.x-20,m.y-28,m.x+20,m.y+23,Color.rgb(112,172,72),Color.rgb(29,66,40),Shader.TileMode.CLAMP); c.drawRoundRect(RectF(m.x-25,m.y-31,m.x+25,m.y+26),12f,12f,paint); paint.shader=null
                text(c,"🌳",m.x-21,m.y+12,38f,Color.WHITE)
                paint.color = Color.rgb(222,221,143); c.drawCircle(m.x-9,m.y-13,3.5f,paint); c.drawCircle(m.x+9,m.y-13,3.5f,paint)
                bar(c,m.x-36,m.y-43,72f,7f,m.hp,m.maxHp,Color.rgb(210,64,64))
                text(c,"THORNHEART",m.x-47,m.y-50,9f,Color.rgb(255,220,140))
                if (m.windup > 0f) text(c,"DODGE!",m.x-24,m.y+42,12f,Color.YELLOW)
            } else {
                val wolf = m.kind == 1
                paint.color = Color.argb(95,0,0,0); c.drawOval(RectF(m.x-17,m.y+6,m.x+18,m.y+18),paint)
                text(c,if(wolf) "🐺" else "🟢",m.x-18,m.y+8,30f,Color.WHITE)
                if(!wolf) text(c,"👀",m.x-8,m.y-5,11f,Color.WHITE)
                bar(c,m.x-18,m.y-24,36f,5f,m.hp,m.maxHp,Color.rgb(207,70,65))
                if (m.windup > 0f) text(c,"!",m.x-3,m.y-28,16f,Color.YELLOW)
            }
        }
        paint.color=Color.argb(80,0,0,0); c.drawOval(RectF(gemmaX-18,gemmaY+6,gemmaX+19,gemmaY+17),paint)
        text(c,"🧙‍♀️",gemmaX-18,gemmaY+9,31f,Color.WHITE)
        text(c,"✨",gemmaX+9,gemmaY-12+ kotlin.math.sin(clock*4f)*3f,15f,Color.WHITE)
        text(c,"GEMMA",gemmaX-23,gemmaY-25,9f,Color.rgb(235,202,255))
        if (invuln <= 0f || (clock * 12f).toInt() % 2 == 0) {
            paint.color=Color.argb(80,0,0,0); c.drawOval(RectF(x-16,y+7,x+17,y+17),paint)
            text(c,when(hero){"Vanguard"->"🧑🏻‍⚔️";"Ranger"->"🧝🏻‍♂️";else->"🧙🏻‍♂️"},x-20,y+9,33f,Color.WHITE)
            if(hitFlash>0f){paint.color=Color.argb(100,255,60,60);c.drawCircle(x,y-3,23f,paint)}
        }
    }

    private fun drawEffects(c: Canvas) {
        effects.forEach { e ->
            val p=(1f-e.life/(if(e.kind==1)0.52f else if(e.kind==4)0.55f else 0.32f)).coerceIn(0f,1f)
            when(e.kind){
                1 -> { val px=e.x+(e.tx-e.x)*p; val py=e.y+(e.ty-e.y)*p; paint.color=Color.rgb(150,104,255);c.drawCircle(px,py,7f*(1f-p)+2f,paint);text(c,"✨",px-8,py+5,18f,Color.WHITE) }
                2 -> { paint.color=Color.argb((220*(1f-p)).toInt(),255,222,107);c.drawLine(e.x,e.y-14,e.x+18f*(1f-p),e.y+8f*(1f-p),paint);c.drawLine(e.x,e.y+10,e.x-17f*(1f-p),e.y-7f,paint);paint.strokeWidth=4f;c.drawCircle(e.tx,e.ty,9f+16f*p,paint);paint.strokeWidth=1f }
                3 -> { paint.color=Color.argb((190*(1f-p)).toInt(),255,65,65);paint.strokeWidth=4f;c.drawCircle(e.x,e.y,12f+30f*p,paint);paint.strokeWidth=1f }
                4 -> { paint.color=Color.argb((150*(1f-p)).toInt(),255,222,126);c.drawCircle(e.x,e.y-12f*p,12f+15f*p,paint);text(c,"💥",e.x-12,e.y-8,20f,Color.WHITE) }
                5 -> { paint.color=Color.argb((200*(1f-p)).toInt(),96,223,255);paint.strokeWidth=4f;c.drawCircle(e.x,e.y,22f+58f*p,paint);paint.strokeWidth=1f;text(c,"⚔️",e.x-12,e.y+6,24f,Color.WHITE) }
            }
        }
    }

    private fun drawHud(c: Canvas) {
        paint.color=Color.argb(225,14,25,27);c.drawRoundRect(RectF(12f,10f,410f,103f),8f,8f,paint)
        text(c,"⭐ STARFALL RPG",24f,31f,16f,Color.rgb(236,220,165))
        text(c,playerName.ifBlank { "Adventurer" }+" · "+hero,24f,49f,11f,Color.rgb(195,220,211))
        text(c,"LV "+level+"  EXP "+exp+"/"+need(level)+"  GOLD "+gold,24f,67f,12f)
        text(c,"PLAYER",24f,68f,10f);bar(c,78f,59f,145f,11f,hp,maxHp,Color.rgb(209,73,74))
        text(c,"GEMMA",24f,84f,10f);bar(c,78f,75f,145f,10f,gemmaHp,gemmaMaxHp,Color.rgb(179,94,211))
        text(c,"KILLS $kills",244f,69f,11f,Color.rgb(218,225,196))
        text(c,"DODGE " + if (dodgeCooldown <= 0f) "READY" else "${dodgeCooldown.toInt()+1}",244f,84f,10f)
        listOf("FOLLOW","ATTACK","GUARD","RANGE").forEachIndexed { i,cmd ->
            val bx=554f+i*96f; paint.color=if(command==cmd) Color.rgb(112,79,151) else Color.argb(220,20,34,34)
            c.drawRoundRect(RectF(bx,14f,bx+88f,46f),6f,6f,paint);text(c,cmd,bx+12f,34f,11f)
        }
        if(messageTime>0f){paint.color=Color.argb(225,12,20,23);c.drawRoundRect(RectF(270f,470f,690f,510f),7f,7f,paint);text(c,message.take(54),284f,495f,12f,Color.rgb(255,235,189))}
        paint.color=Color.argb(135,190,210,190);c.drawCircle(88f,445f,51f,paint)
        paint.color=Color.argb(190,49,90,75);c.drawCircle(88f+moveX*20f,445f+moveY*20f,21f,paint);text(c,"MOVE",65f,510f,10f)
        paint.color=Color.argb(225,135,69,68);c.drawCircle(780f,449f,35f,paint);text(c,"DODGE",752f,454f,10f)
        paint.color=Color.argb(225,103,69,159);c.drawCircle(872f,449f,35f,paint);text(c,"✨",858f,444f,22f);text(c,"SKILL",852f,469f,10f)
        if(skillCooldown>0f) text(c,"${skillCooldown.toInt()+1}s",861f,422f,10f,Color.YELLOW)
        paint.color=Color.argb(225,18,30,34); c.drawRoundRect(RectF(884f,55f,948f,85f),6f,6f,paint); text(c,"MENU",895f,75f,11f,Color.rgb(255,230,172))
    }

    private fun menuButton(c: Canvas, label: String, bx: Float, by: Float, bw: Float, bh: Float, active: Boolean = false) {
        paint.color=if(active) Color.rgb(89,83,132) else Color.rgb(38,65,56)
        c.drawRoundRect(RectF(bx,by,bx+bw,by+bh),7f,7f,paint)
        paint.color=Color.rgb(223,207,161); paint.style=Paint.Style.STROKE
        c.drawRoundRect(RectF(bx,by,bx+bw,by+bh),7f,7f,paint); paint.style=Paint.Style.FILL
        text(c,label,bx+12f,by+bh*0.62f,if(label.length>24) 11f else 14f,Color.WHITE)
    }

    private fun cityBlocked(ax: Float, ay: Float): Boolean {
        if (ax < 28f || ax > 932f || ay < 105f || ay > 455f) return true
        val buildings = listOf(RectF(48f,168f,238f,320f), RectF(258f,168f,448f,320f), RectF(468f,168f,658f,320f))
        return buildings.any { it.contains(ax, ay) }
    }

    private fun updateCity(dt: Float) {
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
        // Asterfall: warm evening sky, distant hills, a plaza, and more detailed storefronts.
        paint.shader=LinearGradient(0f,0f,0f,540f,Color.rgb(52,91,125),Color.rgb(239,173,111),Shader.TileMode.CLAMP);c.drawRect(0f,0f,960f,540f,paint);paint.shader=null
        paint.color=Color.argb(100,255,233,168);c.drawCircle(805f,88f,57f,paint)
        paint.color=Color.rgb(72,103,96);c.drawOval(RectF(-80f,140f,400f,300f),paint);c.drawOval(RectF(330f,125f,760f,300f),paint);c.drawOval(RectF(660f,155f,1060f,310f),paint)
        for(i in 0..8){val bx=i*116f-25f;val hh=65f+(i*29%74);paint.color=if(i%2==0)Color.rgb(77,94,91) else Color.rgb(91,105,96);c.drawRect(bx,224f-hh,bx+88f,292f,paint);paint.color=Color.rgb(218,184,119);for(wx in 0..2)for(wy in 0..2)c.drawRoundRect(RectF(bx+12f+wx*24f,235f-hh+wy*22f,bx+21f+wx*24f,246f-hh+wy*22f),2f,2f,paint)}
        paint.color=Color.rgb(57,104,69);c.drawRect(0f,285f,960f,540f,paint)
        paint.shader=LinearGradient(0f,300f,0f,510f,Color.rgb(190,166,126),Color.rgb(126,112,94),Shader.TileMode.CLAMP);c.drawRoundRect(RectF(0f,306f,960f,510f),10f,10f,paint);paint.shader=null
        paint.color=Color.rgb(209,190,153);c.drawRect(0f,300f,960f,309f,paint)
        for(i in 0..23){val xx=i*42f;paint.color=if(i%2==0)Color.rgb(154,137,111) else Color.rgb(173,153,122);c.drawLine(xx,315f,xx+18f,495f,paint)}
        for(i in 0..17){val yy=320f+i*10f;paint.color=Color.argb(55,54,44,36);c.drawLine(0f,yy,960f,yy+3f,paint)}
        for(g in listOf(18f to 280f,690f to 280f,875f to 286f)){paint.color=Color.rgb(42,78,48);c.drawRoundRect(RectF(g.first,g.second,g.first+65f,g.second+38f),7f,7f,paint);for(k in 0..4){paint.color=Color.rgb(101,157,73);c.drawCircle(g.first+9f+k*12f,g.second+12f+(k%2)*9f,5f,paint)}}
        val buildings=listOf(48f to "🏨",258f to "🛒",468f to "⚒️")
        buildings.forEachIndexed { idx,b ->
            val bx=b.first
            paint.color=Color.argb(75,0,0,0);c.drawRoundRect(RectF(bx+7f,188f,bx+197f,330f),5f,5f,paint)
            paint.color=Color.rgb(91,62,48);c.drawRect(bx,174f,bx+190f,321f,paint)
            paint.color=Color.rgb(190,147,94);c.drawRect(bx+8f,183f,bx+182f,313f,paint)
            paint.color=Color.rgb(129,75,56);c.drawRect(bx+15f,190f,bx+175f,311f,paint)
            paint.color=Color.rgb(108,42,48);c.drawPath(android.graphics.Path().apply{moveTo(bx-10f,184f);lineTo(bx+95f,125f);lineTo(bx+200f,184f);close()},paint)
            paint.color=Color.rgb(222,176,103);c.drawRect(bx+22f,196f,bx+168f,204f,paint)
            for(wx in 0..2){paint.color=Color.rgb(238,205,126);c.drawRoundRect(RectF(bx+24f+wx*49f,216f,bx+51f+wx*49f,246f),3f,3f,paint);paint.color=Color.rgb(58,80,91);c.drawRect(bx+28f+wx*49f,220f,bx+47f+wx*49f,242f,paint)}
            paint.color=Color.rgb(94,52,43);c.drawRoundRect(RectF(bx+70f,259f,bx+120f,321f),4f,4f,paint);paint.color=Color.rgb(202,164,103);c.drawCircle(bx+109f,289f,3f,paint)
            text(c,b.second,bx+79f,173f,25f,Color.WHITE)
            text(c,listOf("THE WAYFARER INN","MERCHANTS ROW","IRON AND EMBER")[idx],bx+23f,164f,10f,Color.rgb(255,238,190))
        }
        text(c,"🏹",700f,353f,34f,Color.WHITE);text(c,"🧙‍♀️",758f,344f,34f,Color.WHITE);text(c,"🧑‍🌾",850f,358f,32f,Color.WHITE);text(c,"🧔",650f,402f,32f,Color.WHITE)
        paint.color=Color.rgb(88,67,94);c.drawRoundRect(RectF(790f,334f,866f,425f),20f,20f,paint);paint.color=Color.rgb(159,116,191);c.drawRoundRect(RectF(800f,344f,856f,415f),17f,17f,paint);paint.shader=RadialGradient(828f,380f,35f,intArrayOf(Color.rgb(166,234,255),Color.rgb(79,92,199),Color.rgb(28,45,74)),null,Shader.TileMode.CLAMP);c.drawCircle(828f,380f,29f,paint);paint.shader=null;text(c,"🌀",808f,389f,34f,Color.WHITE)
        // Hero and Gemma are drawn last and follow the player around the town.
        paint.color=Color.argb(95,0,0,0);c.drawOval(RectF(gemmaX-17,gemmaY+5,gemmaX+18,gemmaY+16),paint);text(c,"🧙‍♀️",gemmaX-18,gemmaY+8,31f,Color.WHITE);text(c,"GEMMA",gemmaX-21,gemmaY-22,9f,Color.rgb(255,224,255))
        paint.color=Color.argb(95,0,0,0);c.drawOval(RectF(x-17,y+6,x+18,y+17),paint)
        val heroEmoji=when(hero){"Vanguard"->"🧑🏻‍⚔️";"Ranger"->"🧝🏻‍♂️";else->"🧙🏻‍♂️"}
        text(c,heroEmoji,x-20,y+9,33f,Color.WHITE);text(c,playerName.ifBlank{"HERO"}.take(12),x-24,y-22,10f,Color.rgb(255,238,185))
        paint.color=Color.argb(218,12,23,25);c.drawRoundRect(RectF(18f,16f,465f,82f),9f,9f,paint);text(c,"🏙️ KOTA ASTERFALL",34f,43f,20f,Color.rgb(255,222,153));text(c,cityMessage.take(52),34f,65f,11f,Color.WHITE)
        paint.color=Color.argb(125,190,210,190);c.drawCircle(88f,445f,49f,paint);paint.color=Color.argb(200,49,90,75);c.drawCircle(88f+moveX*19f,445f+moveY*19f,20f,paint);text(c,"GERAK",60f,510f,10f)
        menuButton(c,"🧑‍🌾 WARGA",30f,420f,150f,42f);menuButton(c,"🛒 TOKO",190f,420f,150f,42f);menuButton(c,"⚒️ PANDAI BESI",350f,420f,180f,42f)
        menuButton(c,"🌀 MASUK HUTAN",690f,475f,240f,45f,true);menuButton(c,"MENU",30f,475f,150f,42f)
    }

    private fun drawMenu(c: Canvas) {
        c.drawColor(Color.rgb(17,34,30))
        for(i in 0..59){paint.color=if(i%2==0) Color.rgb(22,43,36) else Color.rgb(19,38,33);c.drawRect(i*16f,0f,i*16f+16f,540f,paint)}
        paint.color=Color.argb(238,9,20,22);c.drawRoundRect(RectF(24f,18f,936f,522f),12f,12f,paint)
        text(c,"STARFALL RPG",52f,61f,24f,Color.rgb(236,220,165))
        text(c,screen,52f,92f,16f,Color.rgb(201,218,203))
        text(c,playerName.ifBlank{"Nama belum dibuat"}+" · "+hero+" · LV "+level+" · GOLD "+gold,520f,59f,12f,Color.rgb(244,218,143))
        when(screen){
            "MAIN" -> {
                text(c,"Bangun hero, kumpulkan perlengkapan, dan taklukkan hutan.",52f,133f,13f)
                menuButton(c,"MULAI PETUALANGAN",52f,158f,360f,49f,playerName.isNotBlank())
                menuButton(c,"CREATE PLAYER / PILIH HERO",52f,218f,360f,45f)
                menuButton(c,"CHARACTER",52f,274f,170f,43f);menuButton(c,"INVENTORY",242f,274f,170f,43f)
                menuButton(c,"SKILLS",52f,328f,170f,43f);menuButton(c,"EQUIPMENT",242f,328f,170f,43f)
                menuButton(c,"QUEST & MAP",52f,382f,170f,43f);menuButton(c,"SETTINGS",242f,382f,170f,43f)
                text(c,"Offline · Save lokal · Prototype",520f,205f,15f,Color.rgb(166,206,170))
                text(c,"Vanguard: HP tinggi | Ranger: damage tinggi",520f,235f,12f)
                text(c,"Mage: lebih rapuh, skill area lebih kuat",520f,260f,12f)
                menuButton(c,"KOTA ASTERFALL",520f,300f,280f,48f)
            }
            "CITY" -> {
                drawCity(c)
            }
            "CREATE" -> {
                text(c,"Buat nama pemain lalu pilih kelas hero.",52f,133f,13f)
                menuButton(c,if(playerName.isBlank())"ISI NAMA PEMAIN" else "NAMA: "+playerName,52f,158f,360f,46f)
                menuButton(c,"VANGUARD · HP TINGGI",500f,151f,365f,46f,hero=="Vanguard")
                menuButton(c,"RANGER · DAMAGE TINGGI",500f,209f,365f,46f,hero=="Ranger")
                menuButton(c,"MAGE · ARC SLASH KUAT",500f,267f,365f,46f,hero=="Mage")
                text(c,if(hero=="Vanguard")"HP 140 · damage dasar" else if(hero=="Ranger")"HP 110 · damage lebih tinggi" else "HP 90 · skill area lebih kuat",500f,337f,12f,Color.rgb(225,212,171))
                menuButton(c,"SIMPAN KARAKTER",52f,225f,360f,46f);menuButton(c,"KEMBALI",52f,285f,170f,43f)
            }
            "CHARACTER" -> {
                text(c,"Nama: "+playerName.ifBlank{"Belum dibuat"},52f,145f,15f)
                text(c,"Hero: "+hero+"    Level: "+level+"    EXP: "+exp+" / "+need(level),52f,180f,14f)
                text(c,"HP: "+hp.toInt()+" / "+maxHp.toInt()+"    Damage: "+(damage.toInt()+weaponLevel*5),52f,215f,14f)
                text(c,"Armor upgrade: "+armorLevel+"    Weapon upgrade: "+weaponLevel,52f,250f,14f)
                text(c,"Monster dikalahkan: "+kills,52f,285f,14f)
                menuButton(c,"GANTI HERO",52f,330f,220f,44f);menuButton(c,"KEMBALI",52f,390f,220f,43f)
            }
            "INVENTORY" -> {
                text(c,"Ramuan HP × "+potions+" · memulihkan 45 HP",52f,154f,15f,Color.rgb(222,145,145))
                text(c,"Material umum × "+materials,52f,192f,15f,Color.rgb(190,214,167));text(c,"Gold: "+gold,52f,230f,15f,Color.rgb(244,218,143))
                menuButton(c,"GUNAKAN RAMUAN",52f,260f,260f,46f,potions>0&&hp<maxHp);menuButton(c,"KEMBALI",52f,330f,200f,43f)
            }
            "SKILLS" -> {
                text(c,"ARC SLASH · Level "+skillLevel+" / 5",52f,154f,16f,Color.rgb(211,176,244))
                text(c,"Damage area "+(42+(skillLevel-1)*18+weaponLevel*5)+" · cooldown 6 detik",52f,184f,13f)
                menuButton(c,"UPGRADE ARC SLASH · "+(skillLevel*45)+" GOLD",52f,205f,350f,46f,skillLevel<5&&gold>=skillLevel*45)
                text(c,"Skill upgrade menambah damage.",52f,285f,12f);menuButton(c,"KEMBALI",52f,355f,200f,43f)
            }
            "EQUIPMENT" -> {
                text(c,"Senjata: +"+(weaponLevel*5)+" damage",52f,154f,15f)
                menuButton(c,"UPGRADE SENJATA · "+((weaponLevel+1)*55)+" GOLD",52f,175f,350f,46f,weaponLevel<5&&gold>=(weaponLevel+1)*55)
                text(c,"Armor: pengurangan damage "+(armorLevel*3),52f,255f,15f)
                menuButton(c,"UPGRADE ARMOR · "+((armorLevel+1)*50)+" GOLD",52f,276f,350f,46f,armorLevel<5&&gold>=(armorLevel+1)*50)
                menuButton(c,"KEMBALI",52f,360f,200f,43f)
            }
            "QUESTS" -> {
                text(c,"["+(if(kills>=5)"X" else " ")+"] Pemburu pemula: "+kills+"/5 monster",52f,155f,14f)
                text(c,"["+(if(kills>=18)"X" else " ")+"] Tantang Guardian: "+kills+"/18 monster",52f,195f,14f)
                text(c,"["+(if(level>=5)"X" else " ")+"] Tumbuh kuat: capai level 5",52f,235f,14f)
                text(c,"Map saat ini: Hutan Sunyi",52f,280f,14f,Color.rgb(190,214,167));menuButton(c,"KEMBALI",52f,350f,200f,43f)
            }
            "SETTINGS" -> {
                text(c,"Mode offline. Progres karakter tersimpan lokal.",52f,160f,14f)
                text(c,"Joystick kiri · Dodge kanan · Skill Arc Slash.",52f,200f,13f);menuButton(c,"KEMBALI KE MENU UTAMA",52f,270f,300f,46f)
            }
            "PAUSE" -> {
                menuButton(c,"LANJUT BERMAIN",52f,150f,300f,46f);menuButton(c,"CHARACTER",52f,210f,300f,43f)
                menuButton(c,"INVENTORY",52f,265f,300f,43f);menuButton(c,"SKILLS",52f,320f,300f,43f)
                menuButton(c,"EQUIPMENT",52f,375f,300f,43f);menuButton(c,"MENU UTAMA",500f,375f,300f,43f)
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
        fun hit(a:Float,b:Float,w:Float,h:Float)=tx in a..(a+w)&&ty in b..(b+h)
        when(screen){
            "MAIN" -> when{
                hit(52f,158f,360f,49f)->if(playerName.isNotBlank()){screen="CITY";x=745f;y=385f;gemmaX=700f;gemmaY=410f;moveX=0f;moveY=0f;cityMessage="Selamat datang di Asterfall, "+playerName;sfx(ToneGenerator.TONE_PROP_ACK)}else screen="CREATE"
                hit(52f,218f,360f,45f)->screen="CREATE"
                hit(52f,274f,170f,43f)->screen="CHARACTER";hit(242f,274f,170f,43f)->screen="INVENTORY"
                hit(52f,328f,170f,43f)->screen="SKILLS";hit(242f,328f,170f,43f)->screen="EQUIPMENT"
                hit(52f,382f,170f,43f)->screen="QUESTS";hit(242f,382f,170f,43f)->screen="SETTINGS"
                hit(520f,300f,280f,48f)->{screen="CITY";x=745f;y=385f;gemmaX=700f;gemmaY=410f;moveX=0f;moveY=0f;cityMessage="Selamat datang di Asterfall!";sfx(ToneGenerator.TONE_PROP_ACK)}
            }
            "CITY" -> when {
                hit(30f,420f,150f,42f)->{cityMessage="Warga: Monster makin agresif di dalam hutan. Siapkan ramuan!";say(cityMessage,3f)}
                hit(190f,420f,150f,42f)->{cityMessage="Pedagang: Ramuan dan bahan kerajinan akan tersedia di update berikutnya.";say(cityMessage,3f)}
                hit(350f,420f,180f,42f)->{cityMessage="Pandai besi: Upgrade senjata dan armor bisa kamu beli dari menu Equipment.";screen="EQUIPMENT"}
                hit(690f,475f,240f,45f)->{screen="GAME";x=470f;y=285f;gemmaX=425f;gemmaY=320f;moveX=0f;moveY=0f;say("🌀 Portal aktif! Selamat datang di Hutan Sunyi.",3f);sfx(ToneGenerator.TONE_PROP_ACK)}
                hit(30f,475f,150f,42f)->{screen="MAIN";moveX=0f;moveY=0f}
            }
            "CREATE" -> when{
                hit(52f,158f,360f,46f)->showNameDialog()
                hit(500f,151f,365f,46f)->{hero="Vanguard";maxHp=140f;damage=14f+(level-1)*2f;hp=min(hp,maxHp);save()}
                hit(500f,209f,365f,46f)->{hero="Ranger";maxHp=110f;damage=18f+(level-1)*2f;hp=min(hp,maxHp);save()}
                hit(500f,267f,365f,46f)->{hero="Mage";maxHp=90f;damage=14f+(level-1)*2f;hp=min(hp,maxHp);save()}
                hit(52f,225f,360f,46f)->if(playerName.isNotBlank()){when(hero){"Vanguard"->{maxHp=140f;damage=14f+(level-1)*2f};"Ranger"->{maxHp=110f;damage=18f+(level-1)*2f};else->{maxHp=90f;damage=14f+(level-1)*2f}};hp=maxHp;save();screen="MAIN"}else showNameDialog()
                hit(52f,285f,170f,43f)->screen="MAIN"
            }
            "CHARACTER" -> when{hit(52f,330f,220f,44f)->screen="CREATE";hit(52f,390f,220f,43f)->screen="MAIN"}
            "INVENTORY" -> when{
                hit(52f,260f,260f,46f)->if(potions>0&&hp<maxHp){potions--;hp=min(maxHp,hp+45f);save();say("Ramuan digunakan: +45 HP",2f)}else say(if(potions==0)"Ramuan habis." else "HP masih penuh.")
                hit(52f,330f,200f,43f)->screen="PAUSE"
            }
            "SKILLS" -> when{
                hit(52f,205f,350f,46f)->{val cost=skillLevel*45;if(skillLevel<5&&gold>=cost){gold-=cost;skillLevel++;save();say("Arc Slash naik ke Lv."+skillLevel)}else say("Gold kurang atau level maksimum.")}
                hit(52f,355f,200f,43f)->screen="PAUSE"
            }
            "EQUIPMENT" -> when{
                hit(52f,175f,350f,46f)->{val cost=(weaponLevel+1)*55;if(weaponLevel<5&&gold>=cost){gold-=cost;weaponLevel++;save();say("Senjata ditingkatkan.")}else say("Gold kurang atau level maksimum.")}
                hit(52f,276f,350f,46f)->{val cost=(armorLevel+1)*50;if(armorLevel<5&&gold>=cost){gold-=cost;armorLevel++;save();say("Armor ditingkatkan.")}else say("Gold kurang atau level maksimum.")}
                hit(52f,360f,200f,43f)->screen="PAUSE"
            }
            "QUESTS" -> if(hit(52f,350f,200f,43f))screen="PAUSE"
            "SETTINGS" -> if(hit(52f,270f,300f,46f))screen="MAIN"
            "PAUSE" -> when{
                hit(52f,150f,300f,46f)->screen="GAME";hit(52f,210f,300f,43f)->screen="CHARACTER"
                hit(52f,265f,300f,43f)->screen="INVENTORY";hit(52f,320f,300f,43f)->screen="SKILLS"
                hit(52f,375f,300f,43f)->screen="EQUIPMENT";hit(500f,375f,300f,43f)->screen="MAIN"
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
                } else if(tx in 735f..825f && ty>390f && dodgeCooldown<=0f){
                    dodgeTime=0.22f;dodgeCooldown=3f;invuln=0.28f;say("Dodge!",0.7f)
                } else if(tx>825f && ty>390f && skillCooldown<=0f){
                    skillCooldown=6f
                    fx(x,y,x,y,5,0.45f);sfx(ToneGenerator.TONE_PROP_BEEP)
                    val skillDamage = 42f + (skillLevel-1)*18f + weaponLevel*5f
                    mobs.filter{it.alive && dist(it.x,it.y,x,y)<105f}.toList().forEach{it.hp-=skillDamage;if(it.hp<=0f)defeat(it)}
                    say("ARC SLASH Lv."+skillLevel+"!",1f)
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
