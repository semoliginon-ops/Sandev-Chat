#!/usr/bin/env python3
"""Make Starfall's llama.android wrapper report the native load return code."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
target = root / "native-sandev/third_party/llama.cpp/examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
if not target.is_file():
    raise SystemExit(f"Engine source not found: {target}")

source = target.read_text(encoding="utf-8")
old = "if (it != 0) throw UnsupportedArchitectureException()"
new = '''if (it != 0) throw IOException(
                        "Native llama.cpp model load failed (return code=$it). " +
                            "The original UnsupportedArchitectureException is a generic wrapper error; " +
                            "this return code alone does not prove the model architecture is unsupported."
                    )'''
if old not in source:
    if "Native llama.cpp model load failed (return code=$it)" in source:
        print("Native loader diagnostics already applied.")
        raise SystemExit(0)
    raise SystemExit("Expected llama.android load error mapping was not found; refusing an unsafe patch.")
target.write_text(source.replace(old, new, 1), encoding="utf-8")
print("Patched Starfall's generated llama.android wrapper with a more accurate load error.")
