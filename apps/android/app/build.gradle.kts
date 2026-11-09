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

val envKeys = listOf("API_BASE_URL", "SUPABASE_URL", "SUPABASE_ANON_KEY")

android {
    namespace = "com.abodenestly.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abodenestly.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "env"
    productFlavors {
        listOf("dev", "prod").forEach { name ->
            create(name) {
                dimension = "env"
                if (name == "dev") applicationIdSuffix = ".dev"
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
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shopping"))
    implementation(project(":pantry"))
    implementation(project(":maintenance"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
