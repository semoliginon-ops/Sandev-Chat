# SANDEV Game AI Alpha training

This folder builds a small **game-decision fine-tune**, then converts the resulting weights into a GGUF quantized model in GitHub Actions.

- Training entry point: `train_game_ai.py`
- Training examples: generated from multiple game genres and labelled with a transparent scoring policy.
- Output format: GGUF, quantization `Q4_K_M`.
- Intended file ceiling: 150 MB; the workflow fails if the produced file exceeds it.
- Validation: GGUF magic/size check plus a `llama.cpp` inference smoke test.

Honest scope: this is a prototype fine-tune of `HuggingFaceTB/SmolLM2-135M-Instruct`, not a foundation model trained from random initialization. The resulting weights are adapted using SANDEV-generated game-decision examples; merely downloading the base checkpoint would not be the output. The synthetic training labels teach a heuristic policy and do **not** establish expert gameplay. Evaluate on unseen game states and Android hardware before using it in production.

The generated model binary is published as a GitHub Actions artifact rather than committed to ordinary Git history.

Build workflow pins Transformers 4.46.3 to keep the training API reproducible.
