plugins { id("com.android.application") }

val ciBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("1").get().toInt()

android {
    namespace = "com.example.ios27clock"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.ios27clock.digitlab"
        minSdk = 37
        targetSdk = 37
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber-digit-lab"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug { versionNameSuffix = "-debug" }
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":clock-renderer"))
    implementation("com.google.android.material:material:1.13.0")
}
