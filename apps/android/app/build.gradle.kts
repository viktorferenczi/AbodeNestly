import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// SPEC §3 Environments: each flavor reads env/<flavor>.properties into BuildConfig. A missing file
// (prod.properties is git-ignored) yields empty values so other flavors still configure and build.
fun envProperties(flavor: String): Properties = Properties().apply {
    val file = rootProject.file("env/$flavor.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val envKeys = listOf("API_BASE_URL", "WEB_BASE_URL", "SUPABASE_URL", "SUPABASE_ANON_KEY")

android {
    namespace = "com.abodenestly.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abodenestly.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "env"
    productFlavors {
        listOf("dev", "prod").forEach { name ->
            create(name) {
                dimension = "env"
                if (name == "dev") applicationIdSuffix = ".dev"
                // shortcuts.xml (SHOP-15) names the package the App Action targets; res files take no ${applicationId} placeholder.
                resValue("string", "shortcut_target_package", if (name == "dev") "com.abodenestly.app.dev" else "com.abodenestly.app")
                // Magic link and OAuth return here. Dev's scheme is on the allow-list in supabase/config.toml;
                // App Links replace the custom scheme once a domain exists (same note as the iOS README).
                val scheme = if (name == "dev") "abodenestly-dev" else "abodenestly"
                buildConfigField("String", "AUTH_URL_SCHEME", "\"$scheme\"")
                manifestPlaceholders["authScheme"] = scheme
                val props = envProperties(name)
                envKeys.forEach { key ->
                    buildConfigField("String", key, "\"${props.getProperty(key, "")}\"")
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Same managed device as :core, so `:app:pixel6Api34DevDebugAndroidTest` runs the app-level journeys.
        managedDevices {
            localDevices {
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":shared"))
    implementation(project(":contract"))
    implementation(project(":shopping"))
    implementation(project(":pantry"))
    implementation(project(":maintenance"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // 5-minute foreground ticker (SPEC §6.4) follows the process lifecycle.
    implementation(libs.androidx.lifecycle.process)
    implementation(project(":sync"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
