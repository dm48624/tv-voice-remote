plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.masselli.tvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.masselli.tvremote"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // One fixed key so each new build installs over the old one.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/tvremote.jks")
            storePassword = "tvremote"
            keyAlias = "tvremote"
            keyPassword = "tvremote"
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
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
