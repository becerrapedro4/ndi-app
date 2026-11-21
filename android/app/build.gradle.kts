plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Con -PprebuiltNdi=true se reutilizan las librerías nativas ya compiladas de
// app/src/main/jniLibs y no se llama a CMake, que necesita el SDK de NDI para
// Android (propietario y no versionado) sólo para compilar el puente JNI.
val prebuiltNdi = providers.gradleProperty("prebuiltNdi").orNull == "true"

android {
    namespace = "com.ndiapp.camera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ndiapp.camera"
        minSdk = 26
        targetSdk = 34
        versionCode = 41
        versionName = "1.13.5"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        if (!prebuiltNdi) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-Wno-deprecated-declarations")
                }
            }
        }
    }

    if (!prebuiltNdi) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
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
    }
    packaging {
        // Comprime las librerías nativas dentro del APK: el APK pasa de ~21 MB a
        // ~7 MB y Android las extrae al instalar.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Recepción RTSP en el monitor: demux RTP y decodificado con MediaCodec.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
