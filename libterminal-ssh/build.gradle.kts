plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.awkoo.ssh"
    ndkVersion = "29.0.14206865"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path(file("src/main/cpp/CMakeLists.txt"))
            version = "4.1.2"
        }
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    api(project(":libterminal"))
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
}