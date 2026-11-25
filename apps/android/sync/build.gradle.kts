plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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
    testOptions {
        // Robolectric opens real Room files in a temp context.
        unitTests.isIncludeAndroidResources = true
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
    // `Activity` (CORE-08): the local table stores and returns the generated model.
    api(project(":contract"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    // The /sync wire types (SyncWire.kt) are hand-written on top of JsonObject.
    api(libs.kotlinx.serialization.json)
    // SyncWorker / SyncScheduler (SPEC §6.4: periodic 15-minute job while backgrounded).
    api(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
}
