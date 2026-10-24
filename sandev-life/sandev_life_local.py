#!/usr/bin/env python3
"""Local-only GGUF runtime and static server for SANDEV Life."""
import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    from llama_cpp import Llama
except ImportError:
    print("llama-cpp-python belum terpasang. Jalankan: python -m pip install llama-cpp-python", file=sys.stderr)
    raise SystemExit(2)

parser = argparse.ArgumentParser(description="Run SANDEV Game AI locally for SANDEV Life")
parser.add_argument("--model", required=True, help="Path to the existing SANDEV Game AI .gguf file")
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--port", type=int, default=8080)
parser.add_argument("--ctx", type=int, default=2048)
parser.add_argument("--threads", type=int, default=max(1, (os.cpu_count() or 2) - 1))
args = parser.parse_args()
model_path = Path(args.model).expanduser().resolve()
if not model_path.is_file() or model_path.suffix.lower() != ".gguf":
    print(f"File GGUF tidak ditemukan atau bukan .gguf: {model_path}", file=sys.stderr)
    raise SystemExit(2)

print(f"Memuat model lokal: {model_path}")
print("Prompt dan inferensi tetap di perangkat ini; tidak ada panggilan ke AI cloud.")
try:
    llm = Llama(model_path=str(model_path), n_ctx=args.ctx, n_threads=args.threads, verbose=False)
except Exception as exc:
    print(f"Gagal memuat GGUF: {exc}", file=sys.stderr)
    raise SystemExit(3)

ROOT = Path(__file__).resolve().parent
MODEL_NAME = model_path.stem

class Handler(BaseHTTPRequestHandler):
    server_version = "SANDEV-Life-Local/1.0"

    def send_payload(self, status, payload, content_type="application/json; charset=utf-8"):
        if isinstance(payload, bytes):
            data = payload
        elif isinstance(payload, str):
            data = payload.encode("utf-8")
        else:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "http://127.0.0.1:8080")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_payload(204, b"")

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self.send_payload(200, (ROOT / "index.html").read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/health":
            self.send_payload(200, {"ok": True, "model_loaded": True, "model": MODEL_NAME, "model_file": model_path.name, "runtime": "llama-cpp-python", "local_only": True})
        elif self.path == "/v1/models":
            self.send_payload(200, {"object": "list", "data": [{"id": MODEL_NAME, "object": "model", "owned_by": "local"}]})
        else:
            self.send_payload(404, {"error": "not_found"})

    def do_POST(self):
        if self.path.rstrip("/") != "/v1/chat/completions":
            self.send_payload(404, {"error": {"message": "Endpoint tidak ditemukan"}})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            messages = request.get("messages")
            if not isinstance(messages, list) or not messages:
                self.send_payload(400, {"error": {"message": "messages wajib berupa array non-kosong"}})
                return
            result = llm.create_chat_completion(
                messages=messages,
                temperature=max(0.0, min(1.0, float(request.get("temperature", 0.0)))),
                max_tokens=max(1, min(96, int(request.get("max_tokens", 24)))),
            )
            self.send_payload(200, result)
        except Exception as exc:
            self.send_payload(500, {"error": {"message": str(exc)}})

    def log_message(self, fmt, *values):
        print("[SANDEV Life] " + (fmt % values))

server = ThreadingHTTPServer((args.host, args.port), Handler)
print(f"SANDEV Life siap: http://{args.host}:{args.port}")
print("Buka alamat di perangkat yang sama. Tekan Ctrl+C untuk menghentikan server.")
try:
    server.serve_forever()
except KeyboardInterrupt:
    print("\nServer dihentikan.")
finally:
    server.server_close()
