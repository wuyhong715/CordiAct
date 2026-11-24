plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
