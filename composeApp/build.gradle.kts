import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.application")
}

val appVersion = "1.1.0"

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    compilerOptions {
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation("net.java.dev.jna:jna-platform:5.15.0")
        }
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.customtrainableai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.customtrainableai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = appVersion
    }
    signingConfigs {
        // Fester Schlüssel, damit neue APKs über alte installiert werden können.
        create("shared") {
            storeFile = rootProject.file("signing/ctai-debug.keystore")
            storePassword = "android"
            keyAlias = "ctai"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

compose.desktop {
    application {
        mainClass = "com.customtrainableai.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "CustomTrainableAI"
            packageVersion = appVersion
            description = "Selbstlernende KI fuer Spiele"
            modules("java.instrument", "java.management", "jdk.unsupported", "java.naming", "java.sql", "java.net.http")
            windows {
                menuGroup = "CustomTrainableAI"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f4b7a2e-3c1d-4e8a-9b5f-2d7c1e0a9f31"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
