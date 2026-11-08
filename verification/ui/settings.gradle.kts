pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}
rootProject.name = "CordiActUiVerification"
include(":core", ":model-providers", ":mcp-client", ":subagent", ":interaction")
listOf("core", "model-providers", "mcp-client", "subagent", "interaction").forEach { project(":$it").projectDir = file("../../$it") }
