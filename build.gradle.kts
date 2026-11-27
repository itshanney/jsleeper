plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "dev.brickfolio"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
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

tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        // Missing or malformed docs on exported API fail the build.
        addStringOption("Xmaxwarns", "1000")
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Xdoclint/package:-dev.brickfolio.jsleeper.internal", true)  // not public API
        addBooleanOption("Werror", true)
    }
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

testing {
    suites {
        // Unit tests (src/test): offline only. Every HTTP call goes to a local WireMock server.
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
        }

        // Integration tests (src/integrationTest): read-only calls to the real Sleeper API,
        // about 40 per run. Not part of check or build; run with ./gradlew integrationTest.
        // SLEEPER_LEAGUE_ID is inherited from the environment; without it (or .env) the
        // league tests skip and only the not-found probe runs.
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.assertj.core)
            }
            targets.all {
                testTask.configure {
                    description = "Runs the integration tests against the real Sleeper API."
                    System.getenv("SLEEPER_LEAGUE_ID")?.let { environment("SLEEPER_LEAGUE_ID", it) }
                    outputs.upToDateWhen { false }
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "jsleeper"
                description = "A Java client for the read-only Sleeper Fantasy API: typed models, retries, " +
                    "client-side rate limiting and a cached player directory."
                url = "https://github.com/itshanney/jsleeper"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "itshanney"
                        name = "Justin Hanney"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/itshanney/jsleeper.git"
                    developerConnection = "scm:git:ssh://git@github.com/itshanney/jsleeper.git"
                    url = "https://github.com/itshanney/jsleeper"
                }
            }
        }
    }
    repositories {
        // Dry-run target. There is deliberately no remote repository or credentials here.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// Signing happens only when a key is supplied (-PsigningKey=... -PsigningPassword=...), so local
// builds and the dry run need no keys.
signing {
    val signingKey = providers.gradleProperty("signingKey")
    val signingPassword = providers.gradleProperty("signingPassword")
    isRequired = signingKey.isPresent
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
    }
    sign(publishing.publications["mavenJava"])
}
