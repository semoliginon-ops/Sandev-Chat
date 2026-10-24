#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <android/log.h>
#include "llama.h"

namespace {
std::mutex g_mutex;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
const llama_vocab *g_vocab = nullptr;

std::string fromJString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

void unloadLocked() {
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sandev_life_LlamaNative_nativeLoadModel(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unloadLocked();
    const std::string path = fromJString(env, jpath);
    if (path.empty()) return toJString(env, "ERROR: Lokasi model kosong.");
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        unloadLocked();
        return toJString(env, "ERROR: llama.cpp tidak dapat membaca GGUF ini. Pastikan file utuh dan arsitekturnya didukung.");
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::max(256, std::min(2048, static_cast<int>(n_ctx))));
    context_params.n_threads = std::max(1, std::min(4, static_cast<int>(n_threads)));
    context_params.n_threads_batch = context_params.n_threads;
    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        unloadLocked();
        return toJString(env, "ERROR: Model terbaca, tetapi konteks inferensi gagal dibuat. Coba model lebih kecil atau tutup aplikasi lain.");
    }
    g_vocab = llama_model_get_vocab(g_model);
    return toJString(env, "OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sandev_life_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jstring jprompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vocab) return toJString(env, "ERROR: Model belum dimuat.");
    const std::string prompt = fromJString(env, jprompt);
    const int32_t prompt_size = static_cast<int32_t>(prompt.size());
    if (prompt_size == 0) return toJString(env, "ERROR: Prompt kosong.");

    const int32_t needed = -llama_tokenize(g_vocab, prompt.c_str(), prompt_size, nullptr, 0, true, true);
    if (needed <= 0) return toJString(env, "ERROR: Tokenisasi prompt gagal.");
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t token_count = llama_tokenize(g_vocab, prompt.c_str(), prompt_size, tokens.data(), needed, true, true);
    if (token_count < 0) return toJString(env, "ERROR: Tokenisasi prompt gagal.");
    tokens.resize(static_cast<size_t>(token_count));

    const uint32_t context_limit = llama_n_ctx(g_context);
    if (tokens.size() + 8 >= context_limit) {
        return toJString(env, "ERROR: Prompt terlalu panjang untuk konteks model.");
    }
    llama_memory_clear(llama_get_memory(g_context), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_context, batch) != 0) return toJString(env, "ERROR: llama.cpp gagal memproses prompt.");

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sampler_params);
    if (!sampler) return toJString(env, "ERROR: Sampler inferensi gagal dibuat.");
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    std::vector<char> piece(256);
    const int limit = std::max(1, std::min(64, static_cast<int>(max_tokens)));
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(g_vocab, token)) break;
        int piece_len = llama_token_to_piece(g_vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (piece_len < 0) {
            piece.resize(static_cast<size_t>(-piece_len));
            piece_len = llama_token_to_piece(g_vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        }
        if (piece_len > 0) output.append(piece.data(), static_cast<size_t>(piece_len));
        batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(g_context, batch) != 0) break;
        if (output.find('\n') != std::string::npos) break;
    }
    llama_sampler_free(sampler);
    if (output.empty()) return toJString(env, "ERROR: Model tidak menghasilkan token. Periksa kecocokan GGUF dengan runtime.");
    return toJString(env, output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sandev_life_LlamaNative_nativeUnloadModel(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unloadLocked();
    return toJString(env, "OK");
}
