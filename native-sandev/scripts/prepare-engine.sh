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

printf 'Prepared llama.cpp at %s\n' "$PIN"
