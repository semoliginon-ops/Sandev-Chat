#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
DEST="$ROOT/native-sandev/models/local"
FILE="$DEST/SmolLM2-135M-Instruct-Q8_0.gguf"
URL="https://huggingface.co/tensorblock/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q8_0.gguf?download=true"
MAX_BYTES=150000000

mkdir -p "$DEST"
if [ ! -s "$FILE" ]; then
  echo "Downloading candidate game model (~145 MB)..."
  curl --fail --location --retry 3 --output "$FILE.tmp" "$URL"
  mv "$FILE.tmp" "$FILE"
fi

SIZE="$(wc -c < "$FILE" | tr -d ' ')"
if [ "$SIZE" -le 0 ] || [ "$SIZE" -gt "$MAX_BYTES" ]; then
  echo "Model size check failed: $SIZE bytes (limit $MAX_BYTES)." >&2
  rm -f "$FILE"
  exit 1
fi
MAGIC="$(od -An -tx1 -N4 "$FILE" | tr -d ' \n')"
if [ "$MAGIC" != "47475546" ]; then
  echo "Invalid GGUF header: expected magic bytes 47 47 55 46, got $MAGIC." >&2
  rm -f "$FILE"
  exit 1
fi

echo "GGUF header OK"
echo "File: $FILE"
echo "Size: $SIZE bytes"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$FILE"
else
  shasum -a 256 "$FILE"
fi
echo "This only validates the file header and size. Runtime loading and decision quality still require tests on-device."
