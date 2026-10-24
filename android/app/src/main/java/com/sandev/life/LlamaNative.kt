package com.sandev.life

internal object LlamaNative {
    init {
        System.loadLibrary("sandev_llama")
    }

    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): String
    external fun nativeGenerate(prompt: String, maxTokens: Int): String
    external fun nativeUnloadModel(): String
}
