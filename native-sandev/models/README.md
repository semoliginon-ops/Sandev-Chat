# SANDEV Game AI model

## Candidate GGUF (not yet runtime-verified in Sandev Chat)

- Model: [SmolLM2-135M-Instruct-GGUF](https://huggingface.co/tensorblock/SmolLM2-135M-Instruct-GGUF)
- Suggested file: `SmolLM2-135M-Instruct-Q8_0.gguf`
- Reported size: about 145 MB (must be verified from the downloaded file)
- Base license: Apache-2.0, as stated on the model card
- Architecture: Llama-family GGUF
- Chat template shown by the model card: ChatML-style `<|im_start|>` / `<|im_end|>`

This is a small **general instruction model**, not a game-fine-tuned model. It is a candidate for structured game recommendations, not a promise of strong reasoning. The model card notes the family primarily understands and generates English, so Indonesian comments need explicit testing. The deterministic `GameBrain` layer remains the rule-safe fallback.

## Download and inspect

From the repository root, run:

```sh
sh native-sandev/scripts/download-game-model.sh
```

The script stores the downloaded model under `native-sandev/models/local/` (ignored by Git), checks the GGUF magic bytes and file-size ceiling, and prints SHA-256. Do not commit the model binary into ordinary Git history: it exceeds GitHub's normal 100 MB single-file limit. Use a properly configured Git LFS or release/model-hosting workflow if you later decide to redistribute it.

## Before using it in the app

1. Download the model and import the resulting `.gguf` through Sandev Chat's existing model picker.
2. Confirm the pinned `llama.cpp` runtime loads it on arm64-v8a.
3. Run the same game-state prompt suite against the model and the deterministic fallback.
4. Record valid-action rate, invalid-action rate, load time, first-token latency, tokens/second, peak memory and exact file SHA-256.
5. Keep model output advisory. Game rules and legal-action validation stay in Kotlin.
