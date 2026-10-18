package com.sandev.starfalldungeon

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    private val paint = Paint().apply { isAntiAlias = false; typeface = android.graphics.Typeface.MONOSPACE }
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
    private fun save() {
        prefs.edit().putInt("level", level).putInt("exp", exp).putInt("gold", gold).putInt("kills", kills).apply()
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
                    hp -= if (m.kind == 2) 20f else if (m.kind == 1) 12f else 8f
                    say("Kena serangan! Baca tanda lalu dodge.", 1.7f)
                }
                m.attack = if (m.kind == 2) 4f else if (m.kind == 1) 3f else 2.5f
            }
        }

        if (attackTimer <= 0f) {
            val target = mobs.filter { it.alive && dist(it.x, it.y, x, y) < 112f }
                .minByOrNull { dist(it.x, it.y, x, y) }
            if (target != null) {
                target.hp -= damage; attackTimer = 0.7f
                if (target.hp <= 0f) defeat(target) else say("Serangan dasar mengenai target.", 0.65f)
            }
        }
        if (gemmaTimer <= 0f && command != "GUARD") {
            val target = mobs.filter { it.alive && dist(it.x, it.y, gemmaX, gemmaY) < 230f }
                .minByOrNull { dist(it.x, it.y, gemmaX, gemmaY) }
            if (target != null) {
                target.hp -= 10f + (level - 1) * 2f; gemmaTimer = 2f
                say("Gemma: Arcane Bolt!", 0.9f)
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
        if (m.kind == 2) { kills++; return }
        kills++
        if (m.kind == 1) { exp += 22; gold += random.nextInt(6, 11) }
        else { exp += 12; gold += random.nextInt(3, 7) }
        val roll = random.nextInt(100)
        val drop = if (m.kind == 1 && roll < 60) "material umum"
            else if (m.kind == 0 && roll < 30) "material umum"
            else if (roll < 38) "ramuan HP" else ""
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
        update(0.016f); drawForest(canvas); drawCharacters(canvas); drawHud(canvas)
        canvas.restore(); postInvalidateDelayed(16L)
    }
    private fun drawForest(c: Canvas) {
        c.drawColor(Color.rgb(24, 45, 36))
        for (tx in 0..59) for (ty in 0..32) {
            val xx = tx * 16f; val yy = ty * 16f
            paint.color = if ((tx * 7 + ty * 13) % 5 == 0) Color.rgb(34,72,48) else Color.rgb(29,61,42)
            c.drawRect(xx, yy, xx + 16f, yy + 16f, paint)
            if ((tx * 3 + ty * 11) % 17 == 0) { paint.color = Color.rgb(63,98,55); c.drawRect(xx+5,yy+7,xx+8,yy+10,paint) }
        }
        trees.forEach { t ->
            paint.color = Color.rgb(22,43,31); c.drawRect(t.left-4,t.top+7,t.right+4,t.bottom+8,paint)
            paint.color = Color.rgb(79,57,37); c.drawRect(t.centerX()-7,t.centerY(),t.centerX()+7,t.bottom+7,paint)
            paint.color = Color.rgb(32,92,49); c.drawRect(t,paint)
            paint.color = Color.rgb(52,125,60); c.drawRect(t.left+6,t.top+6,t.right-8,t.bottom-10,paint)
            paint.color = Color.rgb(91,153,68); c.drawRect(t.left+13,t.top+10,t.left+22,t.top+19,paint)
        }
        paint.color = Color.rgb(95,78,52); c.drawRect(430f,255f,520f,315f,paint)
        paint.color = Color.rgb(120,101,66); c.drawRect(442f,264f,508f,305f,paint)
    }
    private fun drawCharacters(c: Canvas) {
        mobs.filter { it.alive }.forEach { m ->
            if (m.kind == 2) {
                paint.color = Color.rgb(39,83,47); c.drawRect(m.x-25,m.y-31,m.x+25,m.y+26,paint)
                paint.color = Color.rgb(75,143,61); c.drawRect(m.x-19,m.y-27,m.x+19,m.y+15,paint)
                paint.color = Color.rgb(222,221,143); c.drawRect(m.x-12,m.y-15,m.x-5,m.y-8,paint); c.drawRect(m.x+5,m.y-15,m.x+12,m.y-8,paint)
                bar(c,m.x-36,m.y-43,72f,7f,m.hp,m.maxHp,Color.rgb(210,64,64))
                text(c,"THORNHEART",m.x-47,m.y-50,9f,Color.rgb(255,220,140))
                if (m.windup > 0f) text(c,"DODGE!",m.x-24,m.y+42,12f,Color.YELLOW)
            } else {
                val wolf = m.kind == 1
                paint.color = if (wolf) Color.rgb(73,78,94) else Color.rgb(52,128,72)
                c.drawRect(m.x-14,m.y-10,m.x+14,m.y+11,paint)
                paint.color = if (wolf) Color.rgb(120,127,147) else Color.rgb(90,180,86)
                c.drawRect(m.x-8,m.y-17,m.x+10,m.y+4,paint)
                paint.color = Color.rgb(230,210,125); c.drawRect(m.x+5,m.y-11,m.x+9,m.y-7,paint)
                bar(c,m.x-18,m.y-24,36f,5f,m.hp,m.maxHp,Color.rgb(207,70,65))
                if (m.windup > 0f) text(c,"!",m.x-3,m.y-28,16f,Color.YELLOW)
            }
        }
        // Temporary original pixel-style sprites; no external assets or paid content.
        paint.color=Color.rgb(45,28,54);c.drawRect(gemmaX-10,gemmaY-13,gemmaX+10,gemmaY+13,paint)
        paint.color=Color.rgb(111,71,156);c.drawRect(gemmaX-8,gemmaY-11,gemmaX+8,gemmaY+9,paint)
        paint.color=Color.rgb(191,139,207);c.drawRect(gemmaX-6,gemmaY-17,gemmaX+6,gemmaY-5,paint)
        paint.color=Color.rgb(91,47,126);c.drawRect(gemmaX-9,gemmaY-19,gemmaX+7,gemmaY-13,paint)
        paint.color=Color.rgb(225,210,139);c.drawRect(gemmaX+9,gemmaY-12,gemmaX+13,gemmaY+7,paint)
        text(c,"GEMMA",gemmaX-22,gemmaY-25,9f,Color.rgb(235,202,255))
        if (invuln <= 0f || (clock * 12f).toInt() % 2 == 0) {
            paint.color=Color.rgb(25,45,54);c.drawRect(x-11,y-12,x+11,y+14,paint)
            paint.color=Color.rgb(46,143,155);c.drawRect(x-8,y-10,x+8,y+10,paint)
            paint.color=Color.rgb(227,183,135);c.drawRect(x-6,y-17,x+6,y-5,paint)
            paint.color=Color.rgb(47,77,96);c.drawRect(x-8,y-19,x+7,y-14,paint)
        }
    }
    private fun drawHud(c: Canvas) {
        paint.color=Color.argb(225,14,25,27);c.drawRoundRect(RectF(12f,10f,365f,91f),8f,8f,paint)
        text(c,"STARFALL RPG",24f,32f,16f,Color.rgb(236,220,165))
        text(c,"LV $level  EXP $exp/${need(level)}  GOLD $gold",24f,51f,12f)
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
        paint.color=Color.argb(225,103,69,159);c.drawCircle(872f,449f,35f,paint);text(c,"SKILL",852f,454f,11f)
        if(skillCooldown>0f) text(c,"${skillCooldown.toInt()+1}s",861f,422f,10f,Color.YELLOW)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
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
                    mobs.filter{it.alive && dist(it.x,it.y,x,y)<105f}.toList().forEach{it.hp-=42f;if(it.hp<=0f)defeat(it)}
                    say("ARC SLASH!",1f)
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
