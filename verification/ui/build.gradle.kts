plugins { kotlin("jvm") version "2.4.21" }
subprojects { apply(plugin = "org.jetbrains.kotlin.jvm") }
kotlin {
    jvmToolchain(17)
    sourceSets {
        main { kotlin.srcDir("../../app/src/main/java/com/cordiact/app/experience/model") }
        test { kotlin.srcDir("../../app/src/test/java/com/cordiact/app/experience"); kotlin.exclude("**/ui/**") }
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
