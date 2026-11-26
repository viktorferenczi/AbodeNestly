// Module library. Allowed project deps: :core, :sync, :ui, :shared, :contract — never another module
// (SPEC §2 principle 2, enforced by checkModuleGraph).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.junit5)
}

android {
    namespace = "com.abodenestly.shopping"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    // Same flavors as :core and :sync, so a flavored :sync resolves and `testDevDebugUnitTest` / `lintDevDebug` from the root reach this module.
    flavorDimensions += "env"
    productFlavors {
        create("dev") { dimension = "env" }
        create("prod") { dimension = "env" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Robolectric needs merged resources for the Compose tests.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":sync"))
    implementation(project(":ui"))
    implementation(project(":shared"))
    implementation(project(":contract"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // BackHandler (the placeholder detail's Back).
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    // Runs the JUnit 4 Robolectric/Compose tests on the JUnit Platform beside the JUnit 5 ones.
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
