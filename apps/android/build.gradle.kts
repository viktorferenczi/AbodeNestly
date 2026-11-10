plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.junit5) apply false
}

// SPEC §2 principle 2: a module never depends on another module. Fails the build if :shopping, :pantry or
// :maintenance declares a project dependency on one of the others.
val featureModules = setOf(":shopping", ":pantry", ":maintenance")

val checkModuleGraph by tasks.registering {
    group = "verification"
    description = "Fails if a feature module depends on another feature module."
    val violations = provider {
        featureModules.flatMap { path ->
            project(path).configurations
                .flatMap { config -> config.dependencies.withType<ProjectDependency>().map { config.name to it } }
                .filter { (_, dep) -> dep.path in featureModules && dep.path != path }
                .map { (config, dep) -> "$path → ${dep.path} ($config)" }
        }.distinct()
    }
    doLast {
        val found = violations.get()
        if (found.isNotEmpty()) {
            throw GradleException("Module graph violation (SPEC §2 principle 2):\n" + found.joinToString("\n"))
        }
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(rootProject.tasks.named("checkModuleGraph")) }
}
