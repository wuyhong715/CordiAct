plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    implementation("com.github.mwiede:jsch:0.2.21")
    testImplementation(kotlin("test"))
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    testImplementation("com.github.mwiede:jsch:0.2.21")
}

tasks.test { useJUnitPlatform() }
