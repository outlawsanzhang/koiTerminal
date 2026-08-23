plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.android.virtualization.libs_debian_service"
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

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.com.google.android.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.protobuf.kotlinlite)
    implementation(libs.io.grpc.core)
    implementation(libs.io.grpc.stub)
    implementation(libs.io.grpc.protobuf.lite)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.33.0" }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.83.1"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("java") { option("lite") }
                create("kotlin")
                create("grpc") { option("lite") }
            }
        }
    }
}