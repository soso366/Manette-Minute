plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.manetteminute"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.manetteminute"
        minSdk = 28
        targetSdk = 30          // Fire OS 7/8 (Android 9/11)
        versionCode = 2
        versionName = "1.1"
        // Adresse du serveur pré-remplie sur chaque Fire TV (modifiable ici une fois pour toutes)
        buildConfigField("String", "DEFAULT_SERVER", "\"\"")
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Client ADB en Kotlin : l'appli pilote le Fire TV via son propre débogage réseau
    implementation("dev.mobile:dadb:1.2.10")
}
