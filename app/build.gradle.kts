plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.trellis.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trellis.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1"
    }

    defaultConfig {
        // Real phones are ARM; the x86 Filament libs only serve emulators and
        // cost ~14 MB, so they are left out of the shipped APK.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.version", "META-INF/*.kotlin_module",
            "DebugProbesKt.bin", "kotlin-tooling-metadata.json",
            "META-INF/com/android/build/gradle/*",
        )
    }

    // Release used to inherit no signing config at all, so AGP emitted
    // app-release-unsigned.apk — and Android refuses to install an unsigned
    // APK, which is the "App not installed" error. This keystore is checked in
    // deliberately so every build signs with the same identity and updates
    // install over one another. It is for personal sideloading; a store release
    // needs its own private keystore that is never committed.
    signingConfigs {
        create("void") {
            storeFile = rootProject.file("void-release.jks")
            storePassword = "voidapp"
            keyAlias = "void"
            keyPassword = "voidapp"
            // minSdk is 26, so v1 still matters here alongside the newer schemes.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("void")
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.sceneview)

    // NVIDIA Riva TTS is gRPC-only — there is no JSON/HTTP interface for it.
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.javalite)
    compileOnly(libs.javax.annotation.api)

    // AnimationBaker writes glTF containers by hand, so its output is checked
    // byte for byte on the JVM rather than only on a device.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Generates the Riva TTS stubs. javalite/lite keeps the generated code small
// enough for an app, which the full protobuf runtime would not be.
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
            task.plugins { create("grpc") { option("lite") } }
        }
    }
}
