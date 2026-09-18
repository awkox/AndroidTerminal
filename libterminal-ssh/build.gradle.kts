plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.awkoo.libterminal.ssh"
    ndkVersion = "29.0.14206865"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    externalNativeBuild {
        cmake {
            path(file("src/main/cpp/CMakeLists.txt"))
            version = "4.1.2"
        }
    }
}

kotlin {
    jvmToolchain(21)
}
