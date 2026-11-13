plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.ios27clock.renderer"
    compileSdk = 37
    defaultConfig { minSdk = 37 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
