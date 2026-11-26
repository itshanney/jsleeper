// Jenkins declarative pipeline for jsleeper.
//
// Stages:
//   Build: compile main and test sources, assemble the jar, sources jar and
//          javadoc jar (Javadoc runs with -Xdoclint:all -Werror). No tests run.
//   Test:  run the unit test suite and publish JUnit results to Jenkins.
//
// Integration tests (src/integrationTest, ./gradlew integrationTest) hit the real Sleeper API and are deliberately
// not part of this pipeline; they run in the nightly GitHub Actions workflow.
//
// Requirements on the Jenkins agent:
//   - JDK 25 on the PATH (or uncomment the `tools` block below and point it at
//     a JDK 25 installation configured under Manage Jenkins → Tools).
//   - JUnit plugin (bundled with most Jenkins installs) for the `junit` step.

pipeline {
    agent any

    // tools {
    //     jdk 'jdk-25'   // name of a JDK 25 installation configured in Jenkins
    // }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        // No long-lived Gradle daemon on shared CI agents.
        GRADLE_OPTS = '-Dorg.gradle.daemon=false'
    }

    stages {
        stage('Build') {
            steps {
                sh 'java -version'
                sh './gradlew --no-daemon --stacktrace clean assemble testClasses'
            }
        }

        stage('Test') {
            steps {
                sh './gradlew --no-daemon --stacktrace test'
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: false
                }
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
        }
    }
}
