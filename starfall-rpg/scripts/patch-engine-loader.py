#!/usr/bin/env python3
"""Expose llama.cpp's real GGML load diagnostics in Starfall's model error."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
engine_root = root / "native-sandev/third_party/llama.cpp/examples/llama.android/lib/src/main"
cpp = engine_root / "cpp/ai_chat.cpp"
kt = engine_root / "java/com/arm/aichat/internal/InferenceEngineImpl.kt"
if not cpp.is_file() or not kt.is_file():
    raise SystemExit("Generated llama.android sources were not found; run prepare-engine.sh first.")

c = cpp.read_text(encoding="utf-8")
c = c.replace("#include <unistd.h>\n", "#include <unistd.h>\n#include <mutex>\n", 1) if "#include <mutex>" not in c else c
anchor = "static common_sampler                   * g_sampler;\n"
injection = """static common_sampler                   * g_sampler;
static std::mutex g_starfall_log_mutex;
static std::string g_starfall_load_log;

static void starfall_android_log_callback(enum ggml_log_level level, const char *text, void *user) {
    if (text && (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN)) {
        std::lock_guard<std::mutex> lock(g_starfall_log_mutex);
        g_starfall_load_log.append(text);
        if (g_starfall_load_log.size() > 5000) {
            g_starfall_load_log.erase(0, g_starfall_load_log.size() - 5000);
        }
    }
    aichat_android_log_callback(level, text, user);
}
"""
if "starfall_android_log_callback" not in c:
    if c.count(anchor) != 1:
        raise SystemExit("Could not safely locate native logger insertion point.")
    c = c.replace(anchor, injection, 1)
c = c.replace("llama_log_set(aichat_android_log_callback, nullptr);",
              "llama_log_set(starfall_android_log_callback, nullptr);", 1)
load_anchor = '''    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \\n%s\\n", __func__, model_path);
'''
load_replacement = '''    {
        std::lock_guard<std::mutex> lock(g_starfall_log_mutex);
        g_starfall_load_log.clear();
    }
    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \\n%s\\n", __func__, model_path);
'''
if "g_starfall_load_log.clear();" not in c:
    if c.count(load_anchor) != 1:
        raise SystemExit("Could not safely locate native model-load entry point.")
    c = c.replace(load_anchor, load_replacement, 1)
getter = '''
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getLastModelLoadLogNative(JNIEnv *env, jobject /*unused*/) {
    std::lock_guard<std::mutex> lock(g_starfall_log_mutex);
    return env->NewStringUTF(g_starfall_load_log.c_str());
}

'''
if "Java_com_arm_aichat_internal_InferenceEngineImpl_getLastModelLoadLogNative" not in c:
    c = c.replace("static llama_context *init_context(", getter + "static llama_context *init_context(", 1)
cpp.write_text(c, encoding="utf-8")

k = kt.read_text(encoding="utf-8")
decl_anchor = "    private external fun load(modelPath: String): Int\n"
decl_new = decl_anchor + "\n    @FastNative\n    private external fun getLastModelLoadLogNative(): String\n"
if "getLastModelLoadLogNative" not in k:
    if k.count(decl_anchor) != 1:
        raise SystemExit("Could not safely locate Kotlin native declarations.")
    k = k.replace(decl_anchor, decl_new, 1)
old = "if (it != 0) throw UnsupportedArchitectureException()"
new = '''if (it != 0) {
                        val nativeDetail = getLastModelLoadLogNative().trim().takeLast(1600)
                        throw IOException(
                            "Native llama.cpp model load failed (return code=$it)." +
                                if (nativeDetail.isNotBlank()) "\\nNative detail: $nativeDetail"
                                else "\\nNo native error detail was captured; check GGUF integrity and device memory."
                        )
                    }'''
if old in k:
    k = k.replace(old, new, 1)
elif "Native detail: $nativeDetail" not in k:
    raise SystemExit("Expected generic load-error mapping not found; refusing an unsafe patch.")
kt.write_text(k, encoding="utf-8")
print("Patched llama.cpp to expose native GGUF load diagnostics to Starfall.")
