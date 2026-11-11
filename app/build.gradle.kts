plugins {
    id("com.android.application")
}

android {
    namespace = "com.takayuushi.ios27liquidclock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.takayuushi.ios27liquidclock"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
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
