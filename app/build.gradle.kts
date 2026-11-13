plugins {
    id("com.android.application")
}

// GitHub Actions run numbers are monotonic for this workflow, so every
// downloaded APK is a valid in-place update of the previous one.
val ciBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orElse("2")
    .get()
    .toInt()

android {
    namespace = "com.example.ios27clock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.ios27clock"
        minSdk = 37
        targetSdk = 37
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }
}

dependencies {
    implementation(project(":clock-renderer"))
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
