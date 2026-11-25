import java.net.URI
import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
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
        versionCode = 3
        versionName = "0.5.2-experimental"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    androidResources { noCompress += "task" }
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
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta1")
    implementation("com.google.ar:core:1.57.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
}
// Model pinned to the official version 1. No network is needed at runtime.
val modelDir = layout.buildDirectory.dir("generated/optikerModels")
val fetchFaceModel = tasks.register("fetchFaceModel") {
    val model = modelDir.map { it.file("face_landmarker.task") }
    outputs.file(model)
    doLast {
        val dest = model.get().asFile
        dest.parentFile.mkdirs()
        val tmp = File(dest.parentFile, "face_landmarker.tmp")
        val conn = URI("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task").toURL().openConnection()
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.getInputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        require(tmp.length() > 1000000) { "Modelo facial incompleto" }
        ZipFile(tmp).use { require(it.size() > 0) }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
android.sourceSets.getByName("main").assets.srcDir(modelDir)
tasks.named("preBuild").configure { dependsOn(fetchFaceModel) }
