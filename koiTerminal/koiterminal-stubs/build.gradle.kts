plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.android.virtualization.koiterminal.stubs"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }

    aidlPackagedList += "android/os/IRpcProvider.aidl"
    aidlPackagedList += "android/hardware/security/secretkeeper/ISecretkeeper.aidl"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.com.google.android.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}