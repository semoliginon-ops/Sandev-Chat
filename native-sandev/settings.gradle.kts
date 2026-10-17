pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SandevChatNative"
include(":app")
include(":llama-lib")
project(":llama-lib").projectDir = file("third_party/llama.cpp/examples/llama.android/lib")
