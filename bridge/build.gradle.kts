plugins {
    id("com.android.library") version "9.4.1"
}

android {
    namespace = "io.github.rhinobridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Rhino：纯 Java 实现的 JS 引擎，体积小、无 JIT 依赖，Android 上可直接用。
    implementation("org.mozilla:rhino:1.8.0")
    // dexmaker：javaSubclass 需要在运行时生成抽象类的子类（javaProxy 只能代理接口）。
    implementation("com.linkedin.dexmaker:dexmaker:2.28.5")
}
