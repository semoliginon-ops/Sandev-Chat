# SANDEV Game AI model plan

This directory documents the model artifact; the binary GGUF is intentionally not committed as a text file.

## Target
- Target model file: approximately 150 MB maximum (not a promise until a concrete artifact is measured).
- Runtime: the existing Sandev Chat `llama.cpp` engine.
- Scope: a general game assistant for turn-based, tactics, survival, and objective-driven games.
- Interface: concise structured decision output; the app must validate actions before executing them.

## Important distinction
A prompt or Kotlin decision engine is not a trained GGUF model. Creating a reliable 150 MB GGUF requires selecting an existing model with suitable redistribution terms, checking its tokenizer/chat template and architecture against the pinned engine, then obtaining/quantizing the actual weights and testing them. This repository does not yet contain that model binary.

## Integration contract
The app passes a compact game-state description and a list of legal action IDs. The model may recommend an action, but it must not be trusted to enforce game rules. Game adapters validate the response and use `GameBrain` as deterministic fallback.

## Acceptance checks
1. A valid GGUF metadata header is readable by the app's existing metadata reader.
2. The pinned llama.cpp engine loads the model on arm64-v8a Android.
3. A fixed prompt suite returns an allowed action ID, or the deterministic fallback is used.
4. No illegal action can be executed (invalid target, unavailable resource, or unavailable action).
5. Record model file size, peak memory, first-token latency, tokens/second, and response validity on the target phone.
6. Record source model, license, quantization method, SHA-256, and test results before distributing.

Do not label the model as custom-trained or game-fine-tuned unless an actual training/fine-tuning run and its provenance are recorded.
