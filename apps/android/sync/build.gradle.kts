plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.abodenestly.sync"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    // Same flavors as :core so `testDevDebugUnitTest` / `lintDevDebug` from the root reach this module.
    flavorDimensions += "env"
    productFlavors {
        create("dev") { dimension = "env" }
        create("prod") { dimension = "env" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        // Room's exported schemas are also what migration tests read.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin { jvmToolchain(17) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
}
