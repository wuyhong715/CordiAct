pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "CordiAct"
include(":app", ":core", ":interaction", ":deepseek-harness", ":model-providers", ":search-providers", ":mcp-client", ":subagent")
