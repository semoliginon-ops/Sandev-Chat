# STARFALL RPG — Android prototype

Standalone offline-first top-down 2D action-RPG prototype for Android, targeting lower-end devices such as the Galaxy A05 (6 GB RAM).

## Current foundation
- Landscape forest arena drawn with original low-cost pixel-style shapes (no paid assets or network access).
- Touch joystick, auto-attacks, dodge, Arc Slash, Slime and Wolf enemies.
- Gemma mage companion with deterministic fallback combat and four quick commands.
- EXP curve, gold drops, early level progression and local SharedPreferences save.
- Defeat returns the party to safety without removing gold or EXP.
- Planned Thornheart Guardian encounter after sufficient progress.
- Game continues without a local AI model; GGUF integration is intentionally a later phase.

## Build
From this directory, run `../native-sandev/gradlew --no-daemon assembleDebug`.
GitHub Actions workflow: `.github/workflows/starfall-rpg.yml`.

## Honest status
This is an early gameplay foundation. It has not been validated on a physical Galaxy A05 yet. Pixel-style primitives are temporary original artwork; the next visual pass can replace them with verified free assets whose licenses permit the intended use. Gemma is not yet powered by GGUF inference.
