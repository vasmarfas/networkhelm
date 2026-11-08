import groovy.json.JsonOutput
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode

buildscript {
    dependencies {
        classpath(libs.snakeyamlEngine)
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

val macHost = System.getProperty("os.name").startsWith("Mac")

// a JVM string constant holds at most 65535 bytes, hence the chunks
abstract class BundlePacks : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packs: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun bundle() {
        val directory = packs.get().asFile
        val files = directory.listFiles { file -> file.extension in setOf("json", "yaml", "yml") && !file.name.endsWith(".schema.json") }
            .orEmpty().sortedBy { it.name }
        files.groupBy { it.nameWithoutExtension }.values.firstOrNull { it.size > 1 }
            ?.let { twins -> throw GradleException("one pack in two files: ${twins.joinToString { it.name }}") }
        val menus = files.filter { it.nameWithoutExtension == "menu" || it.nameWithoutExtension.startsWith("menu-") }
        fun literal(file: File) = json(file).chunked(16_000).joinToString(" + ") { chunk ->
            "\"" + chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        }
        val source = buildString {
            appendLine("package com.vasmarfas.networkhelm.packs")
            appendLine()
            appendLine("internal val bundledPackSources: Map<String, String> = mapOf(")
            for (file in files - menus.toSet()) appendLine("    \"${file.name}\" to ${literal(file)},")
            appendLine(")")
            appendLine()
            appendLine("internal val bundledMenuSources: List<String> = listOf(${menus.joinToString(", ") { literal(it) }})")
        }
        val target = output.get().file("com/vasmarfas/networkhelm/packs/BundledPacks.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(source)
    }

    private fun json(file: File): String {
        if (file.extension == "json") return file.readText()
        val root = Compose(LoadSettings.builder().setLabel(file.name).build()).composeString(file.readText())
            .orElseThrow { GradleException("${file.name} is empty") }
        return JsonOutput.toJson(value(root))
    }

    private fun value(node: Node): Any? = when (node) {
        is MappingNode -> node.value.associate { (it.keyNode as ScalarNode).value to value(it.valueNode) }
        is SequenceNode -> node.value.map(::value)
        is ScalarNode -> if (!node.isPlain) node.value else when (node.value) {
            "true" -> true
            "false" -> false
            "", "~", "null" -> null
            else -> node.value
        }
        else -> throw GradleException("unexpected YAML node $node")
    }
}

val bundlePacks = tasks.register<BundlePacks>("bundlePacks") {
    packs.set(rootProject.layout.projectDirectory.dir("packs"))
    output.set(layout.buildDirectory.dir("generated/packs"))
}

abstract class BundleText : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val text: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val constant: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun bundle() {
        val literal = text.get().asFile.readText().chunked(16_000).joinToString(" + ") { chunk ->
            "\"" + chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        }
        val name = constant.get()
        val target = output.get().file(packageName.get().replace('.', '/') + "/" + name.replaceFirstChar { it.uppercase() } + ".kt").asFile
        target.parentFile.mkdirs()
        target.writeText("package ${packageName.get()}\n\ninternal val $name: String = $literal\n")
    }
}

val bundleFields = tasks.register<BundleText>("bundleFields") {
    text.set(layout.projectDirectory.file("data/tik4net-fields.json"))
    packageName.set("com.vasmarfas.networkhelm.routeros")
    constant.set("tik4netFields")
    output.set(layout.buildDirectory.dir("generated/fields"))
}

val bundleAbsent = tasks.register<BundleText>("bundleAbsent") {
    text.set(layout.projectDirectory.file("data/routeros6-absent.json"))
    packageName.set("com.vasmarfas.networkhelm.routeros")
    constant.set("routerOs6Absent")
    output.set(layout.buildDirectory.dir("generated/absent"))
}

val bundleLuci = tasks.register<BundleText>("bundleLuci") {
    text.set(layout.projectDirectory.file("luci/luci-stub.js"))
    packageName.set("com.vasmarfas.networkhelm.openwrt")
    constant.set("luciStub")
    output.set(layout.buildDirectory.dir("generated/luci"))
}

val bundleMibs = tasks.register<BundleText>("bundleMibs") {
    text.set(layout.projectDirectory.file("schema/snmp-mibs.tsv"))
    packageName.set("com.vasmarfas.networkhelm.snmp")
    constant.set("snmpMibs")
    output.set(layout.buildDirectory.dir("generated/mibs"))
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
            // QuickJS has no build for the browser, and its Android build loads only on a device
            group("quickJs") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm && it.name == KotlinCompilation.MAIN_COMPILATION_NAME }
                withIos()
            }
        }
    }

    // cinterop runs only on a Mac; elsewhere iOS gets a stand-in that refuses SSH (src/iosSshStub)
    mapOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (target, name) ->
        if (macHost) {
            target.compilations.getByName("main").cinterops.create("libssh2") {
                definitionFile.set(file("src/nativeInterop/cinterop/libssh2.def"))
                val built = rootProject.layout.projectDirectory.dir("build/ios-ssh/$name")
                includeDirs(built.dir("include"))
                extraOpts("-libraryPath", built.dir("lib").asFile.path)
            }
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
    }

    js {
        browser()
    }

    android {
       namespace = "com.vasmarfas.networkhelm.core"
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
    }
    
    sourceSets {
        commonMain {
            kotlin.srcDir(bundlePacks.flatMap { it.output })
            kotlin.srcDir(bundleLuci.flatMap { it.output })
            kotlin.srcDir(bundleFields.flatMap { it.output })
            kotlin.srcDir(bundleAbsent.flatMap { it.output })
            kotlin.srcDir(bundleMibs.flatMap { it.output })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        getByName("jvmAndroidMain").dependencies {
            implementation(libs.jsch)
        }
        getByName("quickJsMain").dependencies {
            implementation(libs.quickjsKt)
        }
        // Android runs the Java 8 classes of jsch, which need Bouncy Castle for ed25519, curve25519 and chacha20
        androidMain.dependencies {
            implementation(libs.bouncycastle)
        }
        iosMain {
            kotlin.srcDir(if (macHost) "src/iosSsh/kotlin" else "src/iosSshStub/kotlin")
        }
        iosMain.dependencies {
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

val labProperties = rootProject.layout.projectDirectory.file("lab.properties").asFile
tasks.named<Test>("jvmTest") {
    systemProperty("networkhelm.lab", labProperties.path)
    val commands = layout.projectDirectory.file("schema/routeros-commands.json")
    inputs.file(commands)
    systemProperty("networkhelm.commands", commands.asFile.path)
    if (labProperties.exists()) doNotTrackState("lab tests depend on live routers")
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_NETWORKHELM_LAB", labProperties.path)
    if (labProperties.exists()) doNotTrackState("lab tests depend on live routers")
}