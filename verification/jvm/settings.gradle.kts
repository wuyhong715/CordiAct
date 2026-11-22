// JVM-only entry point. Reuse the shipping sources and module builds, without loading AGP.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins { kotlin("jvm") version "2.4.21" }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "CordiActJvmVerification"
val jvmModules = listOf("core", "interaction", "deepseek-harness", "model-providers", "search-providers", "mcp-client", "subagent")
for (module in jvmModules) {
    include(":$module")
    project(":$module").projectDir = file("../../$module")
}
