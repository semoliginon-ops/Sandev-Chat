#!/usr/bin/env python3
"""Patch the archived Alpha source with a safer Android GGUF staging loader."""
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
start = source.index("    private void loadSelectedModel(Uri uri, int contextLength) {")
end = source.index("\n    private String queryDisplayName(Uri uri)", start)
replacement = r'''    private void loadSelectedModel(Uri uri, int contextLength) {
        runOnUiThread(() -> webView.evaluateJavascript("window.onModelLoading && window.onModelLoading()", null));
        new Thread(() -> {
            java.io.File staged = new java.io.File(getFilesDir(), "active-model.gguf");
            java.io.File temp = new java.io.File(getFilesDir(), "active-model.gguf.part");
            try {
                // Release any previous mmap before replacing the staged model file.
                NativeLlama.unload();
                if (temp.exists() && !temp.delete()) throw new IOException("Tidak bisa membersihkan file model sementara.");
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Android tidak bisa membuka file yang dipilih.");
                    try (java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                        byte[] header = new byte[4];
                        int got = 0;
                        while (got < header.length) {
                            int n = bin.read(header, got, header.length - got);
                            if (n < 0) break;
                            got += n;
                        }
                        if (got != 4 || header[0] != 'G' || header[1] != 'G' || header[2] != 'U' || header[3] != 'F') {
                            throw new IOException("File ini tidak memiliki header GGUF yang valid. Pilih model berformat .gguf.");
                        }
                        out.write(header);
                        byte[] buffer = new byte[1024 * 1024];
                        int n;
                        while ((n = bin.read(buffer)) != -1) out.write(buffer, 0, n);
                        out.getFD().sync();
                    }
                }
                if (staged.exists() && !staged.delete()) throw new IOException("Tidak bisa mengganti model lama. Periksa ruang penyimpanan.");
                if (!temp.renameTo(staged)) throw new IOException("Gagal menyiapkan file model di penyimpanan aplikasi. Pastikan ruang kosong cukup.");
                int safeContext = 768; // Match the stable Luna reference context.
                int safeThreads = 2; // Match Luna; avoid oversubscribing mobile CPU cores.
                boolean ok = NativeLlama.loadModel(staged.getAbsolutePath(), safeContext, safeThreads);
                if (!ok) throw new IOException("llama.cpp gagal memuat GGUF ini. Pastikan arsitektur model didukung, ruang RAM cukup, dan coba context 512–1024.");
                String name = queryDisplayName(uri);
                runOnUiThread(() -> webView.evaluateJavascript("window.onModelLoaded(" + org.json.JSONObject.quote(name) + ")", null));
            } catch (Throwable e) {
                if (temp.exists()) temp.delete();
                String msg = e.getMessage() == null ? "Gagal memuat model." : e.getMessage();
                runOnUiThread(() -> webView.evaluateJavascript("window.onModelLoadError(" + org.json.JSONObject.quote(msg) + ")", null));
            }
        }, "sandev-model-loader").start();
    }
'''
path.write_text(source[:start] + replacement + source[end:], encoding="utf-8")
print("Patched GGUF loader:", path)
