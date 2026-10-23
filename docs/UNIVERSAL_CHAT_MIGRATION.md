# Sandev Chat — Universal Chat Migration

## Goal

Keep the existing conversation experience that works well with Gemma 1B while broadening model compatibility. Do not couple chat behavior to a single model family, quantization, or runtime.

## Current implementation

- Android UI and chat orchestration live in native Kotlin in `native-sandev/app/src/main/java/com/sandev/chat/MainActivity.kt`.
- Inference currently uses the Android `llama.cpp` example API (`AiChat` / `InferenceEngine`).
- Model import currently accepts GGUF files and relies on the bundled runtime for architecture/operator compatibility.
- This is not yet the same integration path as PocketPal's React Native `llama.rn` package.
- `llama.rn` is a React Native binding; it cannot be swapped into the existing Kotlin activity as a normal Gradle dependency. Matching PocketPal's runtime requires a React Native integration/migration, or a deliberate native bridge with equivalent behavior.

## Staged implementation

### 1. Preserve and polish the existing experience

- Keep Gemma 1B behavior as the baseline.
- Use clearer, format-neutral GGUF labels rather than implying that only Q4_K_M is supported.
- Bring the Qwen sampling preset closer to PocketPal defaults: temperature 0.7, top-p 0.95, top-k 40, repetition penalty 1.0. Keep output length bounded for mobile use.
- Improve spacing, rounded surfaces, chat bubbles, composer controls, and theme-aware colors.
- Treat this as UI/sampling groundwork, not proof of universal model support.

### 2. Separate chat logic from the current runtime

Introduce a backend-neutral chat contract for model loading, generation streaming, cancellation, runtime status, and error reporting. Keep message history and character prompts outside the backend adapter so they do not depend on one engine.

### 3. Align the local GGUF backend with PocketPal

Integrate `llama.rn` in a React Native chat layer and match relevant initialization and completion settings. Verify model-specific chat templates and context configuration. Avoid changing the existing Gemma experience until regression tests pass.

### 4. Add other backends incrementally

- **GGUF / llama.cpp:** local inference through `llama.rn` for supported architectures.
- **ONNX:** a separate ONNX Runtime backend for compatible model exports.
- **Transformers/Safetensors:** only through a compatible runtime or a conversion path; these files cannot be loaded directly by `llama.rn`.
- **Remote API:** optional adapter for hosted models, with explicit user configuration and network use.

The UI should report unsupported formats and architectures clearly instead of attempting to load every file through one engine.

## Acceptance tests

1. Gemma 1B still loads and chats as before.
2. The same Qwen Base GGUF is compared in Sandev and PocketPal using matched prompts and generation parameters.
3. A Qwen Instruct GGUF is tested separately from Qwen Base.
4. Invalid files and unsupported architectures produce actionable errors.
5. Model switching, chat history, and character prompts remain intact.
6. The Android debug APK builds successfully before a migration step is considered complete.

## Status

The first UI/sampling improvement commit is in place. The runtime migration and additional backend implementations are **not yet complete**; they must be implemented and built in staged changes rather than described as finished prematurely.
