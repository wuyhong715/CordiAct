plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processTestResources { from(projectDir.resolve("../docs/testing/fixtures/extension-packages")) }

tasks.test { useJUnitPlatform() }
