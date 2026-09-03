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

application {
    mainClass.set("club.podlodka.snowball.adapter.cli.GenerateScenariosKt")
}

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.json.schema.validator)
    // The schema validator pulls in slf4j-api; without a provider every run starts with three
    // lines of warning on stderr, which is noise in a tool whose stderr carries the run report.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
}

// The committed schemas under docs/ are the single source of truth; the runtime validator
// needs them on the classpath, so they are copied rather than duplicated by hand.
val copyContractSchemas by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("docs")) {
        include("**/*.schema.json")
        into("contracts")
    }
    into(layout.buildDirectory.dir("generated-resources"))
}

sourceSets.main {
    resources.srcDir(copyContractSchemas)
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
