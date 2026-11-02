plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    testImplementation(kotlin("test"))
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
}

tasks.test { useJUnitPlatform() }
