#!/usr/bin/env python3
"""Apply Sandev's runtime sampling controls to the pinned llama.cpp Android example."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "third_party/llama.cpp/examples/llama.android/lib/src/main"
FILES = {
    "interface": BASE / "java/com/arm/aichat/InferenceEngine.kt",
    "implementation": BASE / "java/com/arm/aichat/internal/InferenceEngineImpl.kt",
    "native": BASE / "cpp/ai_chat.cpp",
}

def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text()
    count = source.count(old)
    if count == 0:
        # Already patched is acceptable only when the replacement is present.
        if new in source:
            return
        raise SystemExit(f"Expected patch anchor not found in {path}: {old[:100]!r}")
    if count != 1:
        raise SystemExit(f"Expected one patch anchor in {path}, found {count}: {old[:100]!r}")
    path.write_text(source.replace(old, new, 1))

replace_once(
    FILES["interface"],
    """    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt""",
    """    suspend fun setSystemPrompt(systemPrompt: String)

    /** Configure token sampling for the currently loaded model. */
    suspend fun setSamplingParams(temperature: Float, topP: Float, topK: Int, repeatPenalty: Float)

    /**
     * Sends a user prompt"""
)

replace_once(
    FILES["implementation"],
    """    @FastNative
    private external fun prepare(): Int

    @FastNative
    private external fun systemInfo(): String""",
    """    @FastNative
    private external fun prepare(): Int

    @FastNative
    private external fun setSamplingParamsNative(temperature: Float, topP: Float, topK: Int, repeatPenalty: Float): Int

    @FastNative
    private external fun systemInfo(): String"""
)

replace_once(
    FILES["implementation"],
    """    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(""",
    """    override suspend fun setSamplingParams(temperature: Float, topP: Float, topK: Int, repeatPenalty: Float) {
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Sampling can only be changed when a model is ready."
        }
        require(temperature in 0.05f..2.0f) { "Temperature must be between 0.05 and 2.0." }
        require(topP in 0.10f..1.0f) { "Top-p must be between 0.10 and 1.0." }
        require(topK in 1..200) { "Top-k must be between 1 and 200." }
        require(repeatPenalty in 1.0f..2.0f) { "Repetition penalty must be between 1.0 and 2.0." }
        val result = setSamplingParamsNative(temperature, topP, topK, repeatPenalty)
        if (result != 0) throw RuntimeException("Failed to update sampling parameters: $result")
    }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt("""
)

replace_once(
    FILES["native"],
    """static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare""",
    """static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setSamplingParamsNative(
        JNIEnv * /*env*/, jobject /*unused*/, jfloat temperature, jfloat top_p,
        jint top_k, jfloat repeat_penalty) {
    if (!g_model || !g_context) return 1;
    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_p = top_p;
    sparams.top_k = top_k;
    sparams.penalty_repeat = repeat_penalty;
    sparams.penalty_last_n = 64;
    auto *new_sampler_instance = common_sampler_init(g_model, sparams);
    if (!new_sampler_instance) return 2;
    if (g_sampler) common_sampler_free(g_sampler);
    g_sampler = new_sampler_instance;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare"""
)

print("Sampling controls added to pinned llama.cpp Android engine.")
