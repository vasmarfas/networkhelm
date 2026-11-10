import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// a JVM string constant holds at most 65535 bytes, hence the chunks
abstract class AboutSource : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val texts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        fun literal(text: String) = text.chunked(16_000).joinToString(" + ") { chunk ->
            "\"" + chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        }
        val source = buildString {
            appendLine("package com.vasmarfas.networkhelm")
            appendLine()
            appendLine("val appVersion: String = ${literal(version.get())}")
            appendLine()
            appendLine("internal val aboutTexts: List<Pair<String, String>> = listOf(")
            for (file in texts.files) appendLine("    \"${file.name}\" to ${literal(file.readText())},")
            appendLine(")")
        }
        val target = output.get().file("com/vasmarfas/networkhelm/About.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(source)
    }
}

val aboutSource = tasks.register<AboutSource>("aboutSource") {
    version.set(rootProject.extra["appVersionName"] as String)
    texts.from(rootProject.files("LICENSE", "NOTICE.md", "THIRD-PARTY-NOTICES.md", "licenses/Apache-2.0.txt", "licenses/LGPL-3.0.txt", "licenses/GPL-3.0.txt", "licenses/EPL-1.0.txt"))
    output.set(layout.buildDirectory.dir("generated/about"))
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
            group("skiko") {
                withJvm()
                withIosArm64()
                withIosSimulatorArm64()
                withWasmJs()
                withJs()
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.vasmarfas.networkhelm.shared")
        }
    }
    
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    js {
        browser()
    }

    android {
       namespace = "com.vasmarfas.networkhelm.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(project(":miknative"))
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.termlib)
        }
        iosMain.dependencies {
            implementation(project(":miknative"))
        }
        commonMain {
            kotlin.srcDir(aboutSource.flatMap { it.output })
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.composeunstyled)
            implementation(libs.composeunstyled.coloredIndication)
            implementation(libs.icons.lucide)
            implementation(libs.materialColorUtilities)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.datetime)
            implementation(libs.filekit.dialogs)
            implementation(libs.ksafe)
        }
        jvmMain.dependencies {
            implementation(project(":server"))
            implementation(project(":miknative"))
            implementation("com.risaboss:bossterm-compose:${libs.versions.bossterm.get()}") {
                exclude(group = "io.modelcontextprotocol")
                exclude(group = "io.github.jan-tennert.supabase")
                exclude(group = "com.google.zxing")
                exclude(group = "io.ktor")
                exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
                exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended-desktop")
                // it pulls the Compose runtime of macOS on ARM onto every OS; the app takes the one of its own OS
                exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
                exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-arm64")
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// Xcode picks the version up from the xcconfig, which the root task rewrites
tasks.matching { it.name.contains("Framework") }.configureEach {
    dependsOn(rootProject.tasks.named("syncIosVersion"))
}

val labProperties = rootProject.layout.projectDirectory.file("lab.properties").asFile
tasks.named<Test>("jvmTest") {
    maxHeapSize = "1g"
    systemProperty("networkhelm.lab", labProperties.path)
    providers.gradleProperty("sweepTimeout").orNull?.let { systemProperty("kotlinx.coroutines.test.default_timeout", it) }
    providers.gradleProperty("nativePort").orNull?.let { systemProperty("networkhelm.nativePort", it) }
    providers.gradleProperty("nativeHost").orNull?.let { systemProperty("networkhelm.nativeHost", it) }
    providers.gradleProperty("macTarget").orNull?.let { systemProperty("networkhelm.macTarget", it) }
    providers.gradleProperty("macFrom").orNull?.let { systemProperty("networkhelm.macFrom", it) }
    providers.gradleProperty("nativeSurvey").orNull?.let { systemProperty("networkhelm.survey", it) }
    providers.gradleProperty("measure").orNull?.let { systemProperty("networkhelm.measure", it) }
    if (labProperties.exists()) doNotTrackState("lab tests depend on live routers")
    mustRunAfter(":core:jvmTest")
}