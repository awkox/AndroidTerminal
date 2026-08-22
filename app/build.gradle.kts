plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.awkoo.terminal"

    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.awkoo.terminal"
        minSdk = 28
        targetSdk = 28
        versionCode = 260
        versionName = "2.6.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("testkey_untrusted.jks")
            keyAlias = "alias"
            storePassword = "xrj45yWGLbsO7W0v"
            keyPassword = "xrj45yWGLbsO7W0v"
        }
        register("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            //noinspection NotShrinkingResources
            isShrinkResources = false // Reproducible builds
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs["release"]
        }

        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs["debug"]
        }
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
    }

    externalNativeBuild {
        cmake {
            path(file("cpp/CMakeLists.txt"))
            version = "4.1.2"
        }
    }

    lint {
        disable.add("ProtectedPermissions")
        disable.add("ExpiredTargetSdkVersion")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.compose.ui.tooling.preview)
    coreLibraryDesugaring(libs.android.desugar)
    implementation(libs.hilt.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
    debugImplementation(libs.leakcanary)
    implementation(libs.google.material)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.composeSettings.ui.extended)
    implementation(libs.serialization.core)
    implementation(libs.serialization.protobuf)
    implementation(libs.timber)
}
