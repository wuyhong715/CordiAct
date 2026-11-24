plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    exclude("**/FollowPipeInteropTest.class")
}

val followTestSource = sourceSets.test.get()
val followNode = providers.gradleProperty("followNode")
tasks.register<Test>("followPipeInteropTest") {
    description = "Run the opt-in Node to Kotlin follow-pipe interop proof."
    group = "verification"
    testClassesDirs = followTestSource.output.classesDirs
    classpath = followTestSource.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    useJUnitPlatform()
    include("**/FollowPipeInteropTest.class")
    inputs.files(fileTree("host-follow") { include("*.mjs", "test/jvm-interop.fixture.mjs") })
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
