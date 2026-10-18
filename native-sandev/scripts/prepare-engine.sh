#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"
PIN="f74e2fa6a31cc33ab4ee4ae516641cfe62df3924"

if [ -d "$DEST/.git" ]; then
  git -C "$DEST" fetch --depth 1 origin "$PIN"
  git -C "$DEST" checkout --detach "$PIN"
else
  mkdir -p "$ROOT/third_party"
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$DEST"
  git -C "$DEST" fetch --depth 1 origin "$PIN"
  git -C "$DEST" checkout --detach "$PIN"
fi

# Keep the dependency checkout reproducible; the local low-memory patch is reapplied below.
git -C "$DEST" reset --hard "$PIN"

ENGINE_CPP="$DEST/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
LIB_GRADLE="$DEST/examples/llama.android/lib/build.gradle.kts"
if grep -q 'DEFAULT_CONTEXT_SIZE    = 8192' "$ENGINE_CPP"; then
  (cd "$DEST" && git apply "$ROOT/patches/lightweight-engine.patch")
fi

# Add model-specific sampling controls after the pinned low-memory patch.
python3 "$ROOT/scripts/patch-sampler.py"

if ! grep -q 'DEFAULT_CONTEXT_SIZE    = 2048' "$ENGINE_CPP" ||
   ! grep -q 'BATCH_SIZE              = 128' "$ENGINE_CPP" ||
   ! grep -q 'Java_com_arm_aichat_internal_InferenceEngineImpl_setSamplingParamsNative' "$ENGINE_CPP" ||
   ! grep -q 'abiFilters += listOf("arm64-v8a")' "$LIB_GRADLE"; then
  echo "Could not verify lightweight engine and sampling patches; refusing to continue." >&2
  exit 1
fi

printf 'Prepared llama.cpp at %s (arm64 only, 2048 context / 128 batch, runtime sampler controls)\n' "$PIN"
