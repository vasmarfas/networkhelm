plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
}

// the one place of the version: every module and the installers take it from here, the pipeline overrides it through the environment
val appVersionName: String = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"

// stores take only a growing integer: 1.4.12-rc2 packs into 1041202, the final 1.4.12 into 1041299
val appVersionCode: Int = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: run {
    val (major, minor, patch) = appVersionName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val stage = if ('-' in appVersionName) appVersionName.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0 else 99
    ((major * 100 + minor) * 100 + patch) * 100 + stage
}

extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode

tasks.register("syncIosVersion") {
    val config = layout.projectDirectory.file("app/iosApp/Configuration/Config.xcconfig").asFile
    val marketing = appVersionName.substringBefore('-')
    val build = appVersionCode
    inputs.property("version", "$marketing+$build")
    outputs.file(config)
    doLast {
        val updated = config.readLines().joinToString("\n") { line ->
            when {
                line.startsWith("MARKETING_VERSION=") -> "MARKETING_VERSION=$marketing"
                line.startsWith("CURRENT_PROJECT_VERSION=") -> "CURRENT_PROJECT_VERSION=$build"
                else -> line
            }
        }
        if (config.readText() != updated) config.writeText(updated)
    }
}
