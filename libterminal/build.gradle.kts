plugins {
    alias(libs.plugins.androidLibrary)
    id("com.vanniktech.maven.publish") version "0.37.0"
}

android {
    namespace = "com.awkoo.libterminal"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    jvmToolchain(21)
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates("io.github.awkox", "libterminal", "2.0.0")

    pom {
        name.set("Android Terminal Library")
        description.set("Android Terminal Library")
        inceptionYear.set("2026")
        url.set("https://github.com/awkox/AndroidTerminal")

        licenses {
            license {
                name.set("GNU General Public License v3.0 or later")
                url.set("https://www.gnu.org/licenses/gpl-3.0-standalone.html")
                comments.set("GPL-3.0-or-later")
            }
        }
        developers {
            developer {
                id.set("awkoo")
                name.set("awkoo")
                url.set("https://github.com/awkox")
            }
        }
        scm {
            url.set("https://github.com/awkox/AndroidTerminal/")
            connection.set("scm:git:git://github.com/awkox/AndroidTerminal.git")
            developerConnection.set("scm:git:ssh://git@github.com/awkox/AndroidTerminal.git")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}