plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    exclude("**/FollowPipeInteropTest.class", "**/FollowTlsInteropTest.class")
}

val followTestSource = sourceSets.test.get()
val followNode = providers.gradleProperty("followNode")
tasks.register<Test>("followPipeInteropTest") {
    description = "Verify Node to Kotlin follow interoperability over pipes and loopback TLS."
    group = "verification"
    testClassesDirs = followTestSource.output.classesDirs
    classpath = followTestSource.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    useJUnitPlatform()
    include("**/FollowPipeInteropTest.class", "**/FollowTlsInteropTest.class")
    inputs.files(fileTree("host-follow") { include("*.mjs", "test/jvm-interop.fixture.mjs", "test/jvm-tls-interop.fixture.mjs") })
    inputs.property("followNode", followNode.orElse(""))
    outputs.upToDateWhen { false }
    doFirst {
        val executable = file(followNode.orNull ?: error("Pass -PfollowNode=/absolute/path/to/node"))
        require(followNode.get() == executable.absolutePath && executable.isFile && executable.canExecute()) {
            "Pass -PfollowNode=/absolute/path/to/node"
        }
        systemProperty("cordiact.follow.node", executable.absolutePath)
    }
}
