plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    application
}

group = "club.podlodka.snowball"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
    // Contract tests read the committed schemas and examples from docs/ instead of copying them.
    systemProperty(
        "snowball.docs.dir",
        layout.projectDirectory
            .dir("docs")
            .asFile.absolutePath,
    )
}
