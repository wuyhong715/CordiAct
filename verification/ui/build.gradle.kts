plugins { kotlin("jvm") version "2.4.21" }
subprojects { apply(plugin = "org.jetbrains.kotlin.jvm") }
kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDir("../../app/src/main/java/com/cordiact/app/experience")
            kotlin.include("model/**", "ToolTracePresentation.kt")
        }
        test {
            kotlin.srcDir("../../app/src/test/java/com/cordiact/app/experience")
            kotlin.include("SidebarModelsTest.kt", "TranscriptTest.kt", "ToolTracePresentationTest.kt")
        }
    }
}
dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
