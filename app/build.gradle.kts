plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.calorias.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calorias.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // Identificador de la compilación (número de ejecución de GitHub Actions) para saber si hay una versión nueva.
        buildConfigField("String", "BUILD_ID", "\"${System.getenv("GITHUB_RUN_ID") ?: "local"}\"")
        // Clave de Google Maps: se pasa como variable de entorno MAPS_API_KEY (nunca va escrita en el código).
        manifestPlaceholders["mapsApiKey"] = System.getenv("MAPS_API_KEY") ?: ""
    }

    // Clave de depuración fija (en el repo) para que todas las compilaciones se firmen igual
    // y Android permita instalar cada versión encima de la anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-shared.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.glance:glance-appwidget:1.1.0")
}
