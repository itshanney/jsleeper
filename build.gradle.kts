plugins {
    `java-library`
}

group = "dev.brickfolio"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ObjectMapper is part of the public builder API, so databind is exposed transitively.
    api(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.wiremock.standalone)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

tasks.test {
    // Live tests call the real Sleeper API; they run only through liveTest.
    useJUnitPlatform {
        excludeTags("live")
    }
}

// Read-only calls against the real API (about 40 in total). Not part of check or build.
// SLEEPER_LEAGUE_ID is inherited from the environment; without it (or .env) the league tests skip.
val liveTest by tasks.registering(Test::class) {
    description = "Runs the @Tag(\"live\") smoke tests against the real Sleeper API."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
    System.getenv("SLEEPER_LEAGUE_ID")?.let { environment("SLEEPER_LEAGUE_ID", it) }
    outputs.upToDateWhen { false }
}
