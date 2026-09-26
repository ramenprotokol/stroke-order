// stroke-order: the matching engine and stroke state machine live in commonMain
// (pure Kotlin, no platform APIs); the browser UI lives in jsMain.
// The JVM target exists only to run commonTest a second time on another platform
// and to run the whole-dataset checks in jvmTest.
plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    jvm()
    js {
        outputModuleName = "stroke-order"
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
                sourceMaps = false
            }
            testTask { enabled = false }
        }
        nodejs()
        binaries.executable()
    }
    jvmToolchain(21)

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // jvmTest reads the committed KanjiVG snapshot from data/.
    workingDir = rootDir
    testLogging { events("failed"); showStandardStreams = true }
}
