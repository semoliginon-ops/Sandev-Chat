# STARFALL RPG — Android prototype

Standalone offline-first, landscape top-down 2D action-RPG prototype for Android, targeting lower-end devices such as the Samsung Galaxy A05.

## Gameplay foundation
- Landscape forest arena, touch joystick, auto-attacks, dodge, Arc Slash, Slime and Wolf enemies.
- Animated hero and Gemma companion, regional enemies, campaign stages, EXP/gold progression, and local SharedPreferences save.
- Gemma has deterministic fallback behavior when a local model is not loaded.

## Local Gemma integration
- The game can import a GGUF file selected from Android storage; the large model is copied in chunks to app-private storage, not packaged into the APK.
- The same pinned llama.cpp Android binding used by the native Sandev Chat project is prepared at build time, with its low-memory 2048 context / 128 batch configuration.
- After the model is loaded, Gemma can answer in-game chat prompts and periodically recommend a whitelisted tactical companion command (ATTACK, KEEP_DISTANCE, FOLLOW, or GUARD).
- The game engine validates the command and executes the movement/command state; the model cannot directly edit save data or call arbitrary game methods.
- Inference runs asynchronously and is not called every frame. The game has a deterministic FOLLOW fallback when no model is active or a tactical response is unusable.
- The GGUF model itself is never bundled into the APK. On first launch, open **STATUS GEMMA** on the main menu, choose **Impor / ganti model GGUF**, and select your local model file. The first load may take time and requires enough free storage for the private copy.

## Build
From this directory, run:

```sh
../native-sandev/scripts/prepare-engine.sh
../native-sandev/gradlew --no-daemon assembleDebug
```

GitHub Actions workflow: `.github/workflows/starfall-rpg.yml`.

## Validation status
This is an integration prototype. CI compilation and on-device testing are still required before claiming the model works reliably while the game is running. A successful compile alone does not prove that the 1.1 GB model can run concurrently with gameplay without lag or memory pressure on every device.
