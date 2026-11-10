import javax.inject.Inject

// ============================================================================
// Fastfetch Android Legacy: the same app for Android 4.1-7.0 (API 16-24).
//
// There is only one copy of the code. The Kotlin sources and resources come
// from app/src/main, and CMake builds the same native-lib.cpp. This module
// only holds what differs: the app ID, SDK levels, NDK, manifest, and the
// PNG launcher icon.
// ============================================================================

plugins {
    alias(libs.plugins.android.application)
}

// ============================================================================
// Shared sources
// ============================================================================

// Copies one shared folder from :app into this module's build folder. A plain
// srcDir pointing at ../app would build too, but Android Studio can't give
// one folder to two modules ("duplicate content roots").
abstract class MirrorSharedSources : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun mirror() {
        files.sync {
            from(sharedDir)
            into(outputDir)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }

        val kotlinMirror =
            tasks.register<MirrorSharedSources>("mirror${variantName}SharedKotlin") {
                sharedDir.set(
                    rootProject.layout.projectDirectory.dir("app/src/main/java")
                )
            }

        val resMirror =
            tasks.register<MirrorSharedSources>("mirror${variantName}SharedRes") {
                sharedDir.set(
                    rootProject.layout.projectDirectory.dir("app/src/main/res")
                )
            }

        variant.sources.kotlin?.addGeneratedSourceDirectory(
            kotlinMirror,
            MirrorSharedSources::outputDir
        )

        variant.sources.res?.addGeneratedSourceDirectory(
            resMirror,
            MirrorSharedSources::outputDir
        )
    }
}

// ============================================================================
// Android
// ============================================================================

android {
    // Same namespace as :app, so R, view binding and the JNI symbol names
    // (Java_com_kaydn_fastfetchandroid_MainActivity_*) all match. The
    // applicationId is what lets it install next to the normal app.
    namespace = "com.kaydn.fastfetchandroid"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kaydn.fastfetchandroid.legacy"
        minSdk = 16
        targetSdk = 24
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a",
                "x86",
                "x86_64"
            )
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }

        // Same idea as :app's bench build: release settings, debug key, its
        // own package name, so it sits next to the real app on a test device.
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // NDK r23c, the last NDK that supports API 16. :app stays on r30.
    ndkVersion = "23.2.8568313"

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // This app targets Android 7.0 on purpose and isn't on Google Play,
        // so Play's target-SDK deadline doesn't apply.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // Same pinned versions as :app. All of them still support API 14, so
    // don't let "newer version available" hints bump them.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
