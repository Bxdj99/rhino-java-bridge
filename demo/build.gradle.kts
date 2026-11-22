plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "io.github.rhinobridge.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rhinobridge.demo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation("androidx.appcompat:appcompat:1.7.0")
}
