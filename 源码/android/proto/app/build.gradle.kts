plugins {
    id("com.android.application")
}

android {
    namespace = "com.ea.proto"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ea.proto"
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}
