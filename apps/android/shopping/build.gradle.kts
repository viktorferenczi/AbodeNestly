// Module library. Allowed project deps: :core, :sync, :ui, :shared, :contract — never another module
// (SPEC §2 principle 2, enforced by checkModuleGraph).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.abodenestly.shopping"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }
