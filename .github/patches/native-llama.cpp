#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <ctime>
#include <chrono>
#include <utility>
#include <unistd.h>
#include "llama.h"

#define LOG_TAG "SandevLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static int g_ctx_size = 768;
// Exact shared prompt prefix from the last request.
static std::vector<llama_token> g_cached_prompt_tokens;

static void unload_locked() {
    g_cached_prompt_tokens.clear();
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

static std::string from_jstring(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sandev_chat_NativeLlama_loadModel(JNIEnv * env, jclass, jstring jpath, jint contextLength, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
    const std::string path = from_jstring(env, jpath);
    if (path.empty()) return JNI_FALSE;

    const auto model_load_start = std::chrono::steady_clock::now();
    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        LOGE("llama_model_load_from_file failed");
        return JNI_FALSE;
    }

    // Keep generation threads conservative, but allow prompt prefill to use
    // a few more CPU workers on multi-core phones.
    const long online_cores = sysconf(_SC_NPROCESSORS_ONLN);
    const int available_cores = online_cores > 0 ? static_cast<int>(online_cores) : 4;
    const int generation_threads = std::max(2, std::min(4, threads > 0 ? static_cast<int>(threads) : available_cores));
    const int batch_threads = std::max(generation_threads, std::min(6, available_cores));
    g_ctx_size = contextLength > 0 ? std::max(512, std::min(1024, static_cast<int>(contextLength))) : 768;
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = g_ctx_size;
    context_params.n_batch = 512;
    context_params.n_threads = generation_threads;
    context_params.n_threads_batch = batch_threads;
    g_ctx = llama_init_from_model(g_model, context_params);
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        unload_locked();
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "timing model_load_ms=%lld context=%d threads=%d batch_threads=%d cpu_cores=%d gpu_layers=0",
        static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - model_load_start).count()),
        g_ctx_size, context_params.n_threads, context_params.n_threads_batch, available_cores);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sandev_chat_NativeLlama_isLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sandev_chat_NativeLlama_unload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

struct PromptParts {
    std::vector<std::string> roles;
    std::vector<std::string> contents;
};

static PromptParts parse_marked_prompt(const std::string & input) {
    PromptParts parsed;
    const std::string system_marker = "[[SANDEV_SYSTEM]]\n";
    const std::string user_marker = "\n[[SANDEV_HISTORY_USER]]\n";
    const std::string assistant_marker = "\n[[SANDEV_HISTORY_ASSISTANT]]\n";
    if (input.rfind(system_marker, 0) != 0) return parsed;

    auto find_next_marker = [&](size_t from) -> std::pair<size_t, std::string> {
        size_t best = std::string::npos;
        std::string role;
        const size_t user_pos = input.find(user_marker, from);
        const size_t assistant_pos = input.find(assistant_marker, from);
        if (user_pos != std::string::npos && (best == std::string::npos || user_pos < best)) {
            best = user_pos;
            role = "user";
        }
        if (assistant_pos != std::string::npos && (best == std::string::npos || assistant_pos < best)) {
            best = assistant_pos;
            role = "assistant";
        }
        return {best, role};
    };

    const size_t content_start = system_marker.size();
    const auto first = find_next_marker(content_start);
    parsed.roles.emplace_back("system");
    parsed.contents.emplace_back(input.substr(content_start,
        first.first == std::string::npos ? std::string::npos : first.first - content_start));

    size_t marker_pos = first.first;
    std::string marker_role = first.second;
    while (marker_pos != std::string::npos) {
        const std::string & marker = marker_role == "assistant" ? assistant_marker : user_marker;
        const size_t body_start = marker_pos + marker.size();
        const auto next = find_next_marker(body_start);
        parsed.roles.emplace_back(marker_role);
        parsed.contents.emplace_back(input.substr(body_start,
            next.first == std::string::npos ? std::string::npos : next.first - body_start));
        marker_pos = next.first;
        marker_role = next.second;
    }
    return parsed;
}

static std::string format_prompt_for_model(const std::string & input) {
    PromptParts parsed = parse_marked_prompt(input);
    if (parsed.roles.empty() || parsed.roles.size() != parsed.contents.size()) return input;

    const char * chat_template = llama_model_chat_template(g_model, nullptr);
    if (chat_template) {
        std::vector<llama_chat_message> messages;
        messages.reserve(parsed.roles.size());
        for (size_t i = 0; i < parsed.roles.size(); ++i) {
            messages.push_back({parsed.roles[i].c_str(), parsed.contents[i].c_str()});
        }
        std::vector<char> buffer(16384);
        const int32_t size = llama_chat_apply_template(chat_template, messages.data(), messages.size(),
            true, buffer.data(), static_cast<int32_t>(buffer.size()));
        if (size > 0 && static_cast<size_t>(size) < buffer.size()) {
            return std::string(buffer.data(), static_cast<size_t>(size));
        }
    }

    // Models without embedded chat templates still get readable role boundaries.
    std::string fallback;
    for (size_t i = 0; i < parsed.roles.size(); ++i) {
        fallback += parsed.roles[i] + ":\n" + parsed.contents[i] + "\n";
    }
    fallback += "assistant:\n";
    return fallback;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sandev_chat_NativeLlama_generate(JNIEnv * env, jclass, jstring jprompt,
    jint maxTokens, jfloat temperature, jfloat topP, jfloat repeatPenalty) {
    const std::string input = from_jstring(env, jprompt);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("Model belum dimuat.");

    const auto generation_start = std::chrono::steady_clock::now();
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const std::string prompt = format_prompt_for_model(input);
    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n_prompt < 0) {
        tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n_prompt <= 0) return env->NewStringUTF("Prompt kosong atau tidak dapat di-tokenisasi.");
    tokens.resize(static_cast<size_t>(n_prompt));
    const auto tokenized_at = std::chrono::steady_clock::now();

    // Preserve the complete model-formatted prompt.
    // Never splice token fragments; fail clearly if the prompt exceeds the safe batch.
    const size_t max_prompt_tokens = 480;
    if (tokens.size() > max_prompt_tokens) {
        return env->NewStringUTF("PROMPT_TOO_LONG: riwayat chat terlalu panjang; mulai percakapan baru atau kurangi context.");
    }

    // Reuse only the exact token prefix shared with the previous prompt. The old
    // generated answer and changed suffix are removed before decoding.
    llama_memory_t memory = llama_get_memory(g_ctx);
    size_t common_prefix = 0;
    while (common_prefix < tokens.size() &&
           common_prefix < g_cached_prompt_tokens.size() &&
           tokens[common_prefix] == g_cached_prompt_tokens[common_prefix]) {
        ++common_prefix;
    }
    // Re-decode the final prompt token so logits belong to this exact prompt.
    if (common_prefix >= tokens.size() && common_prefix > 0) --common_prefix;
    if (common_prefix == 0 || !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common_prefix), -1)) {
        llama_memory_clear(memory, true);
        common_prefix = 0;
    }
    const size_t suffix_size = tokens.size() - common_prefix;
    if (suffix_size > 0) {
        llama_batch batch = llama_batch_get_one(tokens.data() + common_prefix, static_cast<int32_t>(suffix_size));
        if (llama_decode(g_ctx, batch) != 0) {
            llama_memory_clear(memory, true);
            g_cached_prompt_tokens.clear();
            return env->NewStringUTF("DECODE_ERROR: model gagal memproses prompt.");
        }
    }
    g_cached_prompt_tokens = tokens;
    const auto prefill_done = std::chrono::steady_clock::now();
    const auto tokenize_ms = std::chrono::duration_cast<std::chrono::milliseconds>(tokenized_at - generation_start).count();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(prefill_done - tokenized_at).count();
    const double prefill_tokens_per_sec = prefill_ms > 0 ? (1000.0 * n_prompt / prefill_ms) : 0.0;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "timing tokenize_ms=%lld prefill_ms=%lld prompt_tokens=%d reused_prefix=%zu prefill_tokens_per_sec=%.2f",
        static_cast<long long>(tokenize_ms),
        static_cast<long long>(prefill_ms),
        n_prompt, common_prefix, prefill_tokens_per_sec);

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) { llama_memory_clear(llama_get_memory(g_ctx), true); return env->NewStringUTF("SAMPLER_ERROR: sampler tidak dapat dibuat."); }
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64,
        std::max(1.0f, std::min(2.0f, static_cast<float>(repeatPenalty))), 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(std::max(0.05f, std::min(1.0f, static_cast<float>(topP))), 1));
    if (temperature > 0.001f) llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.05f, std::min(2.0f, static_cast<float>(temperature)))));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(std::time(nullptr))));

    std::string output;
    // Enforce the response token limit selected by the app, capped for mobile use.
    const int limit = std::max(1, std::min({80, static_cast<int>(maxTokens), g_ctx_size - n_prompt}));
    int generated_tokens = 0;
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        ++generated_tokens;
        char piece[4096];
        const int piece_size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (piece_size > 0) output.append(piece, static_cast<size_t>(piece_size));
        llama_token next_token = token;
        llama_batch next = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }
    llama_sampler_free(sampler);
    const auto generation_done = std::chrono::steady_clock::now();
    const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_done - prefill_done).count();
    const double generation_tokens_per_sec = generation_ms > 0 ? (1000.0 * generated_tokens / generation_ms) : 0.0;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "timing generation_ms=%lld generated_tokens=%d generation_tokens_per_sec=%.2f output_bytes=%zu",
        static_cast<long long>(generation_ms),
        generated_tokens, generation_tokens_per_sec, output.size());
    if (output.empty()) output = "Model tidak menghasilkan teks. Coba prompt singkat atau model lain.";
    return env->NewStringUTF(output.c_str());
}
