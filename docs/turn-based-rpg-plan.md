# Sandev Chat Turn-Based RPG

This feature is injected into the existing Android WebView build from the archived Alpha source. It keeps the game inside Sandev Chat and leaves the on-device Gemma model/runtime in place.

## Agreed game rules

- Party: player 🦹 and Gemma 🥷 only.
- Level cap: 200 for both characters.
- 50 selectable stages; no world map.
- Enemy counts: stages 1–10: 2; 11–20: 3; 21–30: 4; 31–40: 5; 41–49: 6; stage 50: final boss.
- Every enemy has a unique displayed name, level, combat stats, and a type/ability.
- Menus: main menu, stage selection, inventory, character/equipment, upgrades.
- Battle is turn-based. Player selects an action; Gemma chooses its own action using the existing Sandev model bridge when available, with a tactical fallback if inference fails or returns empty output.
- Gemma battle speech bubbles auto-hide after three seconds. Chatting with Gemma remains outside battle.
- Progression, XP, rewards, and upgrades are balanced together so normal progression approaches level 200 at the end of stage 50, without relying on excessive grinding.

## Implementation approach

The repository stores the Android source project in `sandev-chat-alpha-0.2.zip`. The build workflow extracts that source and applies the existing model/runtime compatibility patches. `.github/scripts/inject_rpg_game.py` then injects the RPG UI and logic into `app/src/main/assets/index.html` during the APK build. This avoids editing the archived ZIP binary and keeps the feature isolated on `feature/turn-based-gemma-rpg`.

## Important validation

A successful APK build verifies source injection and Android packaging, but does not replace on-device gameplay testing. In particular, validate the native `Sandev.generate` bridge behavior, UI responsiveness during inference, save persistence, the stage-50 boss, and level pacing on the target Android device before merging.
