#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ $# -lt 1 ]; then
  echo "Pakai: ./start_local.sh /path/ke/SANDEV-Game-AI-Q4_K_M.gguf"
  exit 1
fi
python3 -m pip show llama-cpp-python >/dev/null 2>&1 || python3 -m pip install llama-cpp-python
exec python3 sandev_life_local.py --model "$1" --host 127.0.0.1 --port 8080
