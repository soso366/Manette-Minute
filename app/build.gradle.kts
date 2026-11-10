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
        // Numéro de version donné automatiquement par GitHub à chaque fabrication
        versionCode = (System.getenv("MM_VERSION") ?: "2").toInt()
        versionName = "1." + (System.getenv("MM_VERSION") ?: "2")
        // Adresse du serveur pré-remplie sur chaque Fire TV (modifiable ici une fois pour toutes)
        buildConfigField("String", "DEFAULT_SERVER", "\"\"")
    }
    buildFeatures { buildConfig = true }

    // Signature fixe : indispensable pour que chaque nouvelle version s'installe par-dessus l'ancienne
    // La clé et son mot de passe viennent des « secrets » GitHub (jamais écrits dans le code).
    val ks = file("signing.keystore")
    val ksPass = System.getenv("MM_KEYSTORE_PASS")
    val signed = ks.exists() && !ksPass.isNullOrEmpty()
    signingConfigs {
        create("borne") {
            if (signed) {
                storeFile = ks
                storePassword = ksPass
                keyAlias = "manetteminute"
                keyPassword = ksPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signed) signingConfigs.getByName("borne") else signingConfigs.getByName("debug")
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
