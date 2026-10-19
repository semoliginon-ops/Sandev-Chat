pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../native-sandev/gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "SandevStarfallRPG"
include(":app")
include(":llama-lib")
project(":llama-lib").projectDir = file("../native-sandev/third_party/llama.cpp/examples/llama.android/lib")
