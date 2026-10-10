plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.linkfill"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.linkfill"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    androidResources { noCompress += "tflite" }

    // QNN needs its Hexagon skel libraries extracted to nativeLibraryDir. Drop the backends and Hexagon versions an
    // SM8850 (HTP V81) never loads, as Nadaka does on the same phone.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("QnnDsp*", "QnnGpu*", "QnnHtpV68*", "QnnHtpV69*", "QnnHtpV73*").map { "lib/arm64-v8a/lib$it.so" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.play.services.nearby)
    implementation(libs.play.services.location)
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.litert)
    implementation(libs.qnn.litert.delegate)
    implementation(libs.qnn.runtime)
    testImplementation(libs.junit)
}
