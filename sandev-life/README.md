# SANDEV Life — AI Living World

SANDEV Life is a separate Android life-simulation game. The Android APK bundles the existing **SANDEV Game AI GGUF Q4_K_M** and the Wllama WebAssembly runtime. Model inference is designed to run locally on the Android device; the game does not call a hosted AI service and does not modify the SANDEV Chat application on `main`.

## Android APK

The branch's GitHub Actions workflow builds an installable debug APK and uploads it as an artifact named **SANDEV-Life-APK**. The build packages the existing model from successful workflow run [#6](https://github.com/semoliginon-ops/Sandev-Chat/actions/runs/40054000856); it does not retrain the model. The APK artifact is retained for 30 days.

The app includes a model-load button. Loading the GGUF model allocates device memory and can take some time; performance depends on the Android phone and WebView version. The APK is large because it includes the model file.

## How decisions work

Each turn, the game constructs a fresh set of legal actions and features, sends the measured state and candidates to SANDEV Game AI, and accepts only a returned candidate ID that belongs to that turn. Invalid or malformed responses are rejected rather than silently replaced with a game-side heuristic.

## Honest limitations

- The existing model was trained on synthetic, heuristic-labelled game decisions across several genres, not a dedicated life-simulation dataset. Its behavior must be evaluated over multiple days and may be inconsistent.
- The Android build packages the model and runtime, but a successful build alone does not prove good gameplay decisions or performance on every phone.
- The model artifact expires on GitHub 23 November 2026 unless renewed; builds need that artifact to remain available.
- The project remains on `feature/sandev-life-local-game`. Do not merge it into `main` unless explicitly requested.
