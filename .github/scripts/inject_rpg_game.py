#!/usr/bin/env python3
"""Inject the emoji-based, turn-based RPG into the archived Sandev Chat WebView UI."""
from pathlib import Path
import sys

project = Path(sys.argv[1])
html_path = project / "app/src/main/assets/index.html"
html = html_path.read_text(encoding="utf-8")
marker = "/* SANDEV_TURN_BASED_RPG_V1 */"
if marker in html:
    print("RPG injection already present; leaving file unchanged.")
    raise SystemExit(0)
if "</body>" not in html.lower():
    raise SystemExit("Cannot inject RPG: expected </body> in app/src/main/assets/index.html")

payload = r'''
<style>
/* SANDEV_TURN_BASED_RPG_V1 */
#sandevRpgLaunch{position:fixed;z-index:9000;right:14px;bottom:18px;border:1px solid #64748b;background:#111827;color:#fff;border-radius:999px;padding:12px 16px;font-weight:800;box-shadow:0 5px 24px #0005}
#sandevRpgRoot{position:fixed;inset:0;z-index:9100;display:none;background:#0b1020;color:#eef2ff;font:14px/1.45 system-ui,-apple-system,sans-serif;overflow:auto}
#sandevRpgRoot *{box-sizing:border-box}
#sandevRpgRoot .rpg-wrap{max-width:760px;margin:auto;padding:16px 14px 28px}
#sandevRpgRoot .rpg-top{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:12px}
#sandevRpgRoot .rpg-title{font-size:22px;font-weight:900;letter-spacing:.2px}
#sandevRpgRoot .rpg-card{background:#151d31;border:1px solid #2c3853;border-radius:16px;padding:14px;margin:10px 0}
#sandevRpgRoot .rpg-row{display:flex;align-items:center;gap:10px}
#sandevRpgRoot .rpg-between{justify-content:space-between}
#sandevRpgRoot .rpg-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}
#sandevRpgRoot button{font:inherit;cursor:pointer;border:1px solid #465674;border-radius:12px;padding:11px 12px;background:#26344f;color:#f8fafc;font-weight:700}
#sandevRpgRoot button.primary{background:#6d28d9;border-color:#8b5cf6}
#sandevRpgRoot button.warn{background:#7f1d1d;border-color:#b91c1c}
#sandevRpgRoot button:disabled{opacity:.4;cursor:not-allowed}
#sandevRpgRoot .rpg-muted{color:#a9b5ce}
#sandevRpgRoot .rpg-big{font-size:32px;line-height:1.1}
#sandevRpgRoot .rpg-name{font-size:16px;font-weight:900}
#sandevRpgRoot .rpg-bar{height:8px;background:#334155;border-radius:9px;overflow:hidden;margin:5px 0 8px}
#sandevRpgRoot .rpg-fill{height:100%;background:#22c55e}
#sandevRpgRoot .rpg-fill.mp{background:#38bdf8}
#sandevRpgRoot .rpg-enemies{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
#sandevRpgRoot .rpg-enemy{border:1px solid #3b4965;border-radius:12px;padding:10px;background:#10182a;text-align:left}
#sandevRpgRoot .rpg-enemy.selected{border-color:#fbbf24;box-shadow:0 0 0 1px #fbbf24}
#sandevRpgRoot .rpg-bubble{background:#312e81;border:1px solid #6366f1;padding:9px 12px;border-radius:12px;margin:9px 0;min-height:36px}
#sandevRpgRoot .rpg-log{max-height:145px;overflow:auto;color:#cbd5e1;font-size:12px}
#sandevRpgRoot .rpg-pill{display:inline-block;border-radius:999px;padding:3px 8px;background:#27344d;margin:2px;font-size:12px}
#sandevRpgRoot .rpg-foot{display:flex;gap:8px;flex-wrap:wrap}
@media(min-width:560px){#sandevRpgRoot .rpg-grid{grid-template-columns:repeat(3,minmax(0,1fr))}#sandevRpgRoot .rpg-enemies{grid-template-columns:repeat(3,minmax(0,1fr))}}
</style>
<button id="sandevRpgLaunch" type="button">⚔️ RPG</button>
<div id="sandevRpgRoot" role="dialog" aria-modal="true" aria-label="Sandev RPG">
  <div class="rpg-wrap" id="sandevRpgScreen"></div>
</div>
<script>
(function(){
'use strict';
if(window.__sandevRpgInstalled)return;window.__sandevRpgInstalled=true;
var root=document.getElementById('sandevRpgRoot'),screen=document.getElementById('sandevRpgScreen');
var launch=document.getElementById('sandevRpgLaunch'),bubbleTimer=null;
var roster=[
 {name:'Grimfang',emoji:'👹',kind:'brute',hp:1.35,atk:1.15,def:1.05,xp:1.0},
 {name:'Venom Widow',emoji:'🕷️',kind:'poison',hp:.85,atk:1.05,def:.8,xp:1.05},
 {name:'Ash Wraith',emoji:'👻',kind:'drain',hp:.9,atk:1.1,def:.9,xp:1.1},
 {name:'Ironhide',emoji:'🦏',kind:'guard',hp:1.5,atk:.95,def:1.55,xp:1.05},
 {name:'Cinder Drake',emoji:'🐉',kind:'burn',hp:1.25,atk:1.3,def:1.0,xp:1.2},
 {name:'Mire Stalker',emoji:'🐊',kind:'bleed',hp:1.15,atk:1.2,def:.95,xp:1.1},
 {name:'Hex Shaman',emoji:'🧙',kind:'hex',hp:.8,atk:1.15,def:.85,xp:1.15},
 {name:'Bone Reaver',emoji:'💀',kind:'heavy',hp:1.1,atk:1.45,def:.85,xp:1.15},
 {name:'Storm Talon',emoji:'🦅',kind:'swift',hp:.85,atk:1.35,def:.75,xp:1.1},
 {name:'Dread Golem',emoji:'🗿',kind:'guard',hp:1.8,atk:1.0,def:1.6,xp:1.25}
];
var state=load();
function fresh(){return {player:{level:1,xp:0,hp:120,maxHp:120,mp:45,maxMp:45,atk:17,def:9,potions:3,coins:0,skillPoints:0,weapon:0,armor:0},gemma:{level:1,xp:0,hp:112,maxHp:112,mp:50,maxMp:50,atk:16,def:10,potions:2},unlocked:1,cleared:0,inventory:{smallPotion:3,largePotion:0,ether:1,ore:0},upgrades:{edge:0,guard:0,focus:0},stage:1,selected:0,battle:null,log:[]};}
function load(){try{var x=JSON.parse(localStorage.getItem('sandevRpgSaveV1'));if(x&&x.player&&x.gemma&&x.inventory)return x;}catch(e){}return fresh();}
function save(){try{localStorage.setItem('sandevRpgSaveV1',JSON.stringify(state));}catch(e){}}
function esc(s){return String(s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function el(id){return document.getElementById(id);}
function xpNeed(level){return 120+level*10;}
function gainXp(who,amount){var c=state[who];c.xp+=Math.max(0,Math.floor(amount));var gained=0;while(c.level<200&&c.xp>=xpNeed(c.level)){c.xp-=xpNeed(c.level);c.level++;gained++;c.maxHp+=who==='player'?7:6;c.maxMp+=who==='player'?2:3;c.atk+=who==='player'?2:2;c.def+=1;c.hp=c.maxHp;c.mp=c.maxMp;}if(c.level>=200)c.xp=0;return gained;}
function stageEnemyCount(n){return n<=10?2:n<=20?3:n<=30?4:n<=40?5:n<=49?6:1;}
function stageName(n){return n===50?'The Last Eclipse':'Stage '+String(n).padStart(2,'0');}
function makeEnemy(stage,i){if(stage===50)return {name:'Azhurak, the World-Ender',emoji:'🐲',kind:'boss',level:200,hp:28000,maxHp:28000,atk:520,def:145,xp:16000,poison:0,burn:0,shield:0,boss:true};var base=roster[(stage*3+i*2-3)%roster.length];var level=Math.min(199,Math.max(1,stage*4-2+i));var hp=Math.floor((70+stage*15+level*9)*base.hp);return {name:base.name,emoji:base.emoji,kind:base.kind,level:level,maxHp:hp,hp:hp,atk:Math.floor((10+stage*3+level*1.4)*base.atk),def:Math.floor((3+stage*1.7+level*.45)*base.def),xp:Math.floor((65+stage*15)*base.xp),poison:0,burn:0,shield:0,boss:false};}
function say(text){var b=el('sandevRpgBubble');if(!b)return;b.textContent='🥷 Gemma: '+text;b.style.display='block';if(bubbleTimer)clearTimeout(bubbleTimer);bubbleTimer=setTimeout(function(){if(b)b.style.display='none';},3000);}
function log(text){if(!state.log)state.log=[];state.log.unshift(text);state.log=state.log.slice(0,18);}
function header(title,back){return '<div class="rpg-top"><div><div class="rpg-title">⚔️ SANDEV RPG</div><div class="rpg-muted">'+esc(title)+'</div></div><button data-action="'+(back||'menu')+'">✕ / Menu</button></div>';}
function bar(label,value,max,cls){var pct=Math.max(0,Math.min(100,100*value/Math.max(1,max)));return '<div class="rpg-row rpg-between"><span>'+label+'</span><span>'+Math.max(0,Math.floor(value))+' / '+max+'</span></div><div class="rpg-bar"><div class="rpg-fill '+(cls||'')+'" style="width:'+pct+'%"></div></div>';}
function partyCard(who,emoji,name){var c=state[who];return '<div class="rpg-card"><div class="rpg-row"><div class="rpg-big">'+emoji+'</div><div><div class="rpg-name">'+name+' · Lv.'+c.level+'</div><div class="rpg-muted">ATK '+c.atk+' · DEF '+c.def+'</div></div></div>'+bar('HP',c.hp,c.maxHp,'')+bar('MP',c.mp,c.maxMp,'mp')+'<div class="rpg-muted">XP '+c.xp+' / '+xpNeed(c.level)+'</div></div>';}
function menu(){state.battle=null;render('<div class="rpg-top"><div><div class="rpg-title">⚔️ Sandev RPG</div><div class="rpg-muted">Duo adventure · Level cap 200 · 50 stages</div></div><button data-action="close">Kembali ke Chat</button></div>'+partyCard('player','🦹','Player')+partyCard('gemma','🥷','Gemma')+'<div class="rpg-grid"><button class="primary" data-action="stages">🗡️ Pilih Stage</button><button data-action="bag">🎒 Bag / Inventory</button><button data-action="character">🧑 Character</button><button data-action="upgrade">⬆️ Upgrade</button><button data-action="chat">💬 Chat dengan Gemma</button><button data-action="reset" class="warn">Reset Save</button></div><div class="rpg-card"><b>Progress</b><p class="rpg-muted">Stage terbuka: '+state.unlocked+'/50 · Stage selesai: '+state.cleared+'/50 · Koin: '+state.player.coins+'</p><p class="rpg-muted">Battle dirancang sulit sejak awal. Siapkan potion, skill, dan keputusan Gemma.</p></div>');}
function render(html){screen.innerHTML=html;root.style.display='block';save();}
function stages(){var h=header('Pilih Stage')+'<div class="rpg-card"><p class="rpg-muted">Musuh per rentang: 1–10 = 2 · 11–20 = 3 · 21–30 = 4 · 31–40 = 5 · 41–49 = 6 · Stage 50 = Final Boss.</p><div class="rpg-grid">';for(var i=1;i<=50;i++){h+='<button data-stage="'+i+'" '+(i>state.unlocked?'disabled':'')+' class="'+(i===state.unlocked?'primary':'')+'">'+i+'. '+esc(stageName(i))+(i<=state.cleared?' ✓':'')+'</button>';}h+='</div></div>';render(h);}
function bag(){var inv=state.inventory;render(header('Bag / Inventory')+'<div class="rpg-card"><div class="rpg-row rpg-between"><span>🧪 Small Potion · Pulihkan 35% HP</span><b>'+inv.smallPotion+'</b></div><button data-item="smallPotion">Gunakan di luar battle</button><hr><div class="rpg-row rpg-between"><span>❤️ Large Potion · Pulihkan 70% HP</span><b>'+inv.largePotion+'</b></div><button data-item="largePotion">Gunakan di luar battle</button><hr><div class="rpg-row rpg-between"><span>🔷 Ether · Pulihkan 30% MP</span><b>'+inv.ether+'</b></div><button data-item="ether">Gunakan Ether</button><hr><div class="rpg-row rpg-between"><span>⛏️ Upgrade Ore</span><b>'+inv.ore+'</b></div></div>');}
function character(){render(header('Character & Equipment')+partyCard('player','🦹','Player')+partyCard('gemma','🥷','Gemma')+'<div class="rpg-card"><b>Equipment Player</b><p>Weapon tier '+state.player.weapon+' · Armor tier '+state.player.armor+'</p><p class="rpg-muted">Upgrade permanen meningkatkan statistik; keduanya tetap berkembang hingga level 200.</p></div>');}
function upgrade(){var u=state.upgrades;render(header('Upgrade')+'<div class="rpg-card"><b>Koin: '+state.player.coins+' · Ore: '+state.inventory.ore+'</b><p>Edge +4 ATK (biaya '+(100+u.edge*130)+' koin)</p><button data-upgrade="edge">Upgrade Edge · Lv.'+u.edge+'</button><p>Guard +3 DEF dan +5% HP maksimum (biaya '+(100+u.guard*130)+' koin)</p><button data-upgrade="guard">Upgrade Guard · Lv.'+u.guard+'</button><p>Focus +5 MP maksimum dan +2 ATK Gemma (biaya '+(150+u.focus*160)+' koin)</p><button data-upgrade="focus">Upgrade Focus · Lv.'+u.focus+'</button><p class="rpg-muted">Koin dan ore didapat dari kemenangan. Simpan resource untuk stage yang makin berat.</p></div>');}
function startBattle(n){state.stage=n;var count=stageEnemyCount(n),enemies=[];for(var i=0;i<count;i++)enemies.push(makeEnemy(n,i));state.battle={stage:n,enemies:enemies,turn:1,selected:0,defending:false,gemmaDefending:false,over:false,bossPhase:0};state.log=[];log('Stage '+n+' dimulai. Musuh: '+count+'.');battle();}
function battle(){var b=state.battle;if(!b)return;var alive=b.enemies.filter(function(e){return e.hp>0;});if(!alive.length){victory();return;}var h=header(stageName(b.stage),'menu')+'<div class="rpg-card"><div class="rpg-row rpg-between"><b>Stage '+b.stage+' · Turn '+b.turn+'</b><span class="rpg-pill">'+alive.length+' musuh tersisa</span></div><div class="rpg-enemies">';b.enemies.forEach(function(e,i){h+='<button class="rpg-enemy '+(b.selected===i?'selected':'')+'" data-target="'+i+'" '+(e.hp<=0?'disabled':'')+'><div class="rpg-big">'+e.emoji+'</div><div class="rpg-name">'+esc(e.name)+'</div><div class="rpg-muted">Lv.'+e.level+' · ATK '+e.atk+' · DEF '+e.def+'</div>'+bar('HP',e.hp,e.maxHp,'')+'</button>';});h+='</div><div id="sandevRpgBubble" class="rpg-bubble" style="display:none"></div></div>'+partyCard('player','🦹','Player')+partyCard('gemma','🥷','Gemma')+'<div class="rpg-card"><b>Giliran Player</b><div class="rpg-grid"><button class="primary" data-combat="attack">⚔️ Attack</button><button data-combat="skill">✨ Skill · 18 MP</button><button data-combat="defend">🛡️ Defend</button><button data-combat="potion">🧪 Potion</button></div><p class="rpg-muted">Pilih target dengan mengetuk kartu musuh. Gemma akan memilih aksi sendiri setelah giliranmu.</p></div><div class="rpg-card"><b>Battle log</b><div class="rpg-log">'+(state.log||[]).map(function(x){return '<div>• '+esc(x)+'</div>';}).join('')+'</div></div>';render(h);if(bubbleTimer){clearTimeout(bubbleTimer);bubbleTimer=null;}}
function damage(attacker,defender,power){var variance=.85+Math.random()*.3;return Math.max(1,Math.floor((attacker.atk*power-defender.def*.55)*variance));}
function aliveEnemies(){return state.battle.enemies.filter(function(e){return e.hp>0;});}
function playerAction(action){var b=state.battle;if(!b||b.over)return;var p=state.player,target=b.enemies[b.selected];if(!target||target.hp<=0)target=aliveEnemies()[0];if(!target)return;if(action==='attack'){var d=damage(p,target,1);target.hp=Math.max(0,target.hp-d);log('Player menyerang '+target.name+' sebesar '+d+' damage.');}
else if(action==='skill'){if(p.mp<18){log('MP tidak cukup untuk skill.');battle();return;}p.mp-=18;var d2=damage(p,target,1.85);target.hp=Math.max(0,target.hp-d2);log('Shadow Break mengenai '+target.name+' sebesar '+d2+' damage.');}
else if(action==='defend'){b.defending=true;log('Player bertahan; damage masuk berkurang.');}
else if(action==='potion'){if(p.hp<p.maxHp*.65&&state.inventory.smallPotion>0){state.inventory.smallPotion--;p.hp=Math.min(p.maxHp,p.hp+Math.floor(p.maxHp*.35));log('Player memakai Small Potion.');}else if(state.inventory.largePotion>0&&p.hp<p.maxHp*.8){state.inventory.largePotion--;p.hp=Math.min(p.maxHp,p.hp+Math.floor(p.maxHp*.7));log('Player memakai Large Potion.');}else if(p.potions>0&&p.hp<p.maxHp*.65){p.potions--;p.hp=Math.min(p.maxHp,p.hp+Math.floor(p.maxHp*.3));log('Player memakai potion cadangan.');}else{log('Potion tidak tersedia atau HP belum perlu dipulihkan.');battle();return;}}
battle();if(!state.battle)return;setTimeout(gemmaTurn,100);}
function chooseFallback(){var g=state.gemma,b=state.battle,alive=aliveEnemies();if(g.hp<g.maxHp*.34&&state.inventory.smallPotion>0)return {action:'potion'};if(g.mp>=18&&alive.some(function(e){return e.hp<=g.atk*1.5;}))return {action:'skill',target:alive[0]};if(g.hp<g.maxHp*.45)return {action:'defend'};return {action:g.mp>=18&&g.mp>g.maxMp*.3?'skill':'attack',target:alive.slice().sort(function(a,b){return a.hp-b.hp;})[0]};}
function parseGemma(text){text=String(text||'').toUpperCase();var action=(text.match(/\b(ATTACK|SKILL|DEFEND|POTION)\b/)||[])[1];var n=(text.match(/\bTARGET\s*[:=]?\s*(\d+)\b/)||text.match(/\b(ATTACK|SKILL)\s+(\d+)\b)||[]);var idx=Number(n[n.length-1]);if(!action)return null;return {action:action.toLowerCase(),index:Number.isFinite(idx)&&idx>0?idx-1:-1};}
function decideGemma(){var b=state.battle,g=state.gemma,p=state.player,alive=aliveEnemies();var fallback=chooseFallback();if(!window.Sandev||typeof window.Sandev.generate!=='function')return fallback;var compact=alive.map(function(e,i){return (i+1)+': '+e.name+' Lv'+e.level+' HP '+e.hp+'/'+e.maxHp+' ATK '+e.atk;}).join('; ');var prompt='[[SANDEV_SYSTEM]]\nKamu Gemma, companion taktis dalam RPG turn-based. Pilih aksi paling berguna untuk memenangkan battle. Pertimbangkan HP, MP, musuh, dan keselamatan party. Jawab HANYA satu baris: ATTACK 1, SKILL 1, DEFEND, atau POTION. Nomor target mengikuti daftar musuh hidup berikut. Jangan menulis penjelasan.\n[[SANDEV_HISTORY_USER]]\nHP Gemma '+g.hp+'/'+g.maxHp+', MP '+g.mp+'/'+g.maxMp+'. HP player '+p.hp+'/'+p.maxHp+'. Potion tersedia '+state.inventory.smallPotion+'. Musuh: '+compact+'\n[[SANDEV_HISTORY_ASSISTANT]]\n';try{var out=window.Sandev.generate(prompt,18,.15,.85,1.05);if(out&&typeof out.then==='function')return out.then(function(t){return parseGemma(t)||fallback;}).catch(function(){return fallback;});return parseGemma(out)||fallback;}catch(e){log('AI Gemma gagal merespons; fallback taktis dipakai.');return fallback;}}
function gemmaTurn(){var b=state.battle;if(!b||b.over)return;var decision=decideGemma();if(decision&&typeof decision.then==='function'){decision.then(runGemmaAction).catch(function(){runGemmaAction(chooseFallback());});}else runGemmaAction(decision||chooseFallback());}
function runGemmaAction(decision){var b=state.battle;if(!b||b.over)return;var g=state.gemma,alive=aliveEnemies();if(!alive.length){victory();return;}var action=decision.action||'attack',target=null;if(decision.index>=0)target=alive[decision.index];if(!target)target=decision.target;if(!target||target.hp<=0)target=alive.slice().sort(function(a,b){return a.hp-b.hp;})[0];if(action==='potion'&&g.hp<g.maxHp*.7&&state.inventory.smallPotion>0){state.inventory.smallPotion--;g.hp=Math.min(g.maxHp,g.hp+Math.floor(g.maxHp*.35));say('Aku pulihkan diri dulu. Kita masih harus bertahan!');log('Gemma memakai Small Potion.');}
else if(action==='defend'){b.gemmaDefending=true;say('Aku bertahan untuk mengurangi damage berikutnya.');log('Gemma mengambil posisi bertahan.');}
else if(action==='skill'&&g.mp>=18){g.mp-=18;var d=damage(g,target,1.75);target.hp=Math.max(0,target.hp-d);say('Aku pakai Shadow Kunai pada '+target.name+'!');log('Gemma memakai skill: '+d+' damage ke '+target.name+'.');}
else {var d2=damage(g,target,1);target.hp=Math.max(0,target.hp-d2);say('Aku serang '+target.name+'!');log('Gemma menyerang '+target.name+' sebesar '+d2+' damage.');}
if(!aliveEnemies().length){victory();return;}renderBattleAfterGemma();}
function renderBattleAfterGemma(){var b=state.battle;if(!b)return;var enemies=aliveEnemies();if(!enemies.length){victory();return;}var delay=450;setTimeout(function(){if(!state.battle)return;enemyTurn();},delay);}
function enemyTurn(){var b=state.battle;if(!b||b.over)return;var p=state.player,g=state.gemma;var alive=aliveEnemies();alive.forEach(function(e,i){if(e.hp<=0)return;var target=(Math.random()<.55)?p:g;var defense=(target===p&&b.defending)||(target===g&&b.gemmaDefending);var raw=damage(e,target,e.kind==='heavy'||e.kind==='boss'?1.35:1);var amount=defense?Math.max(1,Math.floor(raw*.4)):raw;if(e.kind==='poison'&&Math.random()<.3){target.hp=Math.max(1,target.hp-Math.floor(target.maxHp*.04));log(e.name+' memberikan racun!');}if(e.kind==='burn'&&Math.random()<.25){target.hp=Math.max(1,target.hp-Math.floor(target.maxHp*.03));log(e.name+' membakar target!');}target.hp=Math.max(0,target.hp-amount);log(e.name+' menyerang '+(target===p?'Player':'Gemma')+' sebesar '+amount+'.');if(e.boss&&b.bossPhase%3===2){var other=target===p?g:p;var burst=Math.floor(e.atk*.6);other.hp=Math.max(0,other.hp-burst);log('Azhurak melepas Eclipse Burst ke '+(other===p?'Player':'Gemma')+' sebesar '+burst+' damage!');}if(e.boss)b.bossPhase++;});b.defending=false;b.gemmaDefending=false;if(p.hp<=0&&g.hp<=0){defeat();return;}b.turn++;battle();}
function victory(){var b=state.battle;if(!b||b.over)return;b.over=true;var stage=b.stage;var enemyXp=b.enemies.reduce(function(s,e){return s+e.xp;},0);var reward=60+130*stage+enemyXp;var coin=40+stage*18;var pl=gainXp('player',reward),gm=gainXp('gemma',Math.floor(reward*.95));state.player.coins+=coin;state.inventory.ore+=Math.max(1,Math.floor(stage/5));if(stage%5===0)state.inventory.largePotion++;if(stage%3===0)state.inventory.smallPotion+=2;state.cleared=Math.max(state.cleared,stage);state.unlocked=Math.min(50,Math.max(state.unlocked,stage+1));state.battle=null;var extra='';if(pl||gm)extra='<p>Level up: Player +'+pl+' · Gemma +'+gm+'</p>';render(header('Stage Clear')+'<div class="rpg-card"><div class="rpg-big">🏆</div><h2>Stage '+stage+' Selesai!</h2><p>XP: '+reward+' · Koin: '+coin+' · Ore: '+Math.max(1,Math.floor(stage/5))+'</p>'+extra+'<p>Stage berikutnya: '+Math.min(50,stage+1)+'</p><div class="rpg-foot"><button class="primary" data-action="next" '+(stage===50?'disabled':'')+'>Stage Berikutnya</button><button data-action="stages">Pilih Stage</button><button data-action="menu">Menu Utama</button></div></div>'+partyCard('player','🦹','Player')+partyCard('gemma','🥷','Gemma'));}
function defeat(){state.battle=null;render(header('Party Kalah')+'<div class="rpg-card"><div class="rpg-big">💀</div><h2>Party tumbang.</h2><p>Musuh terlalu kuat kali ini. Upgrade equipment, pulihkan HP, dan atur penggunaan potion sebelum mencoba lagi.</p><button class="primary" data-action="retry">Coba Lagi</button> <button data-action="upgrade">Upgrade</button> <button data-action="menu">Menu Utama</button></div>');}
function act(action){if(action==='close'){root.style.display='none';return;}if(action==='menu'){menu();return;}if(action==='stages'){stages();return;}if(action==='bag'){bag();return;}if(action==='character'){character();return;}if(action==='upgrade'){upgrade();return;}if(action==='chat'){root.style.display='none';return;}if(action==='next'){startBattle(Math.min(50,state.stage+1));return;}if(action==='retry'){startBattle(state.stage);return;}if(action==='reset'){if(confirm('Hapus seluruh progres RPG?')){state=fresh();menu();}return;}}
screen.addEventListener('click',function(ev){var b=ev.target.closest('button');if(!b)return;if(b.dataset.action){act(b.dataset.action);return;}if(b.dataset.stage){startBattle(Number(b.dataset.stage));return;}if(b.dataset.target!==undefined){state.battle.selected=Number(b.dataset.target);battle();return;}if(b.dataset.combat){playerAction(b.dataset.combat);return;}if(b.dataset.item){var key=b.dataset.item,c=state.player;if(state.inventory[key]>0){state.inventory[key]--;if(key==='ether')c.mp=Math.min(c.maxMp,c.mp+Math.floor(c.maxMp*.3));else c.hp=Math.min(c.maxHp,c.hp+Math.floor(c.maxHp*(key==='largePotion'?.7:.35)));}bag();return;}if(b.dataset.upgrade){var key=b.dataset.upgrade,cost=key==='focus'?150+state.upgrades[key]*160:100+state.upgrades[key]*130;if(state.player.coins<cost){alert('Koin belum cukup.');return;}state.player.coins-=cost;state.upgrades[key]++;if(key==='edge'){state.player.atk+=4;state.player.weapon++;}else if(key==='guard'){state.player.def+=3;state.player.maxHp+=5;state.player.hp+=5;state.player.armor++;}else{state.player.maxMp+=5;state.player.mp+=5;state.gemma.atk+=2;}upgrade();}});
launch.addEventListener('click',menu);
window.addEventListener('keydown',function(e){if(e.key==='Escape'&&root.style.display==='block')root.style.display='none';});
})();
</script>
'''
html = html.replace("</body>", payload + "\n</body>", 1)
html_path.write_text(html, encoding="utf-8")
print("Injected Sandev RPG into:", html_path)
