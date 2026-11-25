optiker-medidas-android
└── app
    ├── build.gradle.kts
    └── src
        └── main
            ├── AndroidManifest.xml
            ├── java
            │   └── com
            │       └── optiker
            │           └── medidas
            │               ├── arquivos antigos da V4
            │               └── v5
            │                   ├── CameraProfiler.kt
            │                   ├── DenseFaceMeshEngine.kt
            │                   ├── V5FaceOverlayView.kt
            │                   ├── V5MeasurementActivity.kt
            │                   └── V5Models.kt
            └── res
                └── layout
                    └── activity_v5_measurement.xmlplugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.optiker.medidas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.optiker.medidas"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.13.0")

    val camerax = "1.7.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Modelo facial empacotado no APK: não precisa baixar no primeiro uso.
    implementation("com.google.mlkit:face-detection:16.1.7")
}
