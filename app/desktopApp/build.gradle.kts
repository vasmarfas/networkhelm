import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// the windows draw their title bar through the custom title bar of the JetBrains Runtime; -PlegacyJava=true builds the flavour
// for Windows 7 and 8, where Java 17 is the last runtime that loads and the window keeps the title bar of the system
val legacy = providers.gradleProperty("legacyJava").orNull == "true"
val arm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
val flavor = if (legacy) "-legacy" else if (arm) "-arm64" else ""

val jetBrainsRuntime: JavaToolchainSpec.() -> Unit = {
    languageVersion.set(JavaLanguageVersion.of(if (legacy) 17 else 25))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

layout.buildDirectory.set(layout.projectDirectory.dir(if (legacy) "build/legacy" else "build"))

kotlin {
    jvmToolchain(jetBrainsRuntime)
    sourceSets.main { kotlin.setSrcDirs(listOf(if (legacy) "src/legacy/kotlin" else "src/main/kotlin")) }
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    if (!legacy) {
        implementation(libs.jewel.decoratedWindow) {
            // the icons bring IntelliJ's fork of kotlinx.coroutines 1.10 in the same packages, and Ktor needs 1.11
            exclude(group = "org.jetbrains.intellij.deps.kotlinx")
        }
    }

    implementation(libs.compose.uiToolingPreview)
}

// pty4j keeps the executable it starts local processes with in its jar, and Compose signs only the libraries packed in jars;
// notarization refuses an executable without the hardened runtime, and the terminal of the app runs over SSH, not a local process.
// jbr-api seals com.jetbrains: in an installed app the class data archive comes from another folder, a class taken from it defines
// the package unsealed, and the next class loaded from the jar stopped the start with "can't seal package com.jetbrains"
abstract class PatchJars : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val jar: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val source = jar.get().asFile
        if (!source.name.startsWith("pty4j-") && !source.name.startsWith("jbr-api-")) {
            outputs.file(source)
            return
        }
        ZipFile(source).use { zip ->
            ZipOutputStream(outputs.file(source.name).outputStream()).use { target ->
                for (entry in zip.entries()) {
                    if (entry.name == "resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper") continue
                    if (entry.name == JarFile.MANIFEST_NAME) {
                        val manifest = zip.getInputStream(entry).use { Manifest(it) }
                        manifest.mainAttributes.remove(Attributes.Name.SEALED)
                        manifest.entries.values.forEach { it.remove(Attributes.Name.SEALED) }
                        target.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                        manifest.write(target)
                    } else {
                        target.putNextEntry(ZipEntry(entry).apply { compressedSize = -1 })
                        zip.getInputStream(entry).use { it.copyTo(target) }
                    }
                    target.closeEntry()
                }
            }
        }
    }
}

val patched = Attribute.of("networkhelm.patchedJars", Boolean::class.javaObjectType)
dependencies {
    attributesSchema { attribute(patched) }
    artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) { attributes.attribute(patched, false) }
    registerTransform(PatchJars::class) {
        from.attribute(patched, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        to.attribute(patched, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
    }
}
configurations.runtimeClasspath { attributes.attribute(patched, true) }

val innoSetup = listOfNotNull(
    providers.environmentVariable("INNO_SETUP").orNull,
    providers.environmentVariable("LOCALAPPDATA").orNull?.let { "$it\\Programs\\Inno Setup 6\\ISCC.exe" },
    "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe",
).firstOrNull { File(it).isFile }

val appCds = tasks.register("appCds") {
    group = "compose desktop"
    description = "Writes the class data archives of the Windows app image"
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    val image = layout.buildDirectory.dir("compose/binaries/main/app/NetworkHelm").get().asFile
    val home = temporaryDir
    doLast {
        val config = image.resolve("app/NetworkHelm.cfg")
        val plain = config.readText()
        fun options(vararg added: String) = plain.replace("[JavaOptions]", (listOf("[JavaOptions]") + added.map { "java-options=$it" }).joinToString(System.lineSeparator()))
        fun start(vararg added: String) {
            config.writeText(options(*added))
            val launcher = ProcessBuilder(image.resolve("NetworkHelm.exe").path).redirectErrorStream(true).redirectOutput(home.resolve("launcher.log"))
            launcher.environment()["NETWORKHELM_HOME"] = home.path
            check(launcher.start().waitFor(5, TimeUnit.MINUTES)) { "the launcher did not end within 5 minutes" }
        }
        try {
            start("-Xshare:dump")
            start("-XX:ArchiveClassesAtExit=\$APPDIR\\networkhelm.jsa", "-Dnetworkhelm.appcds=true")
        } finally {
            config.writeText(plain)
        }
        check(image.resolve("runtime/bin/server/classes.jsa").isFile && image.resolve("app/networkhelm.jsa").isFile) { "no class data archive was written" }
        check("jdk.accessibility" in image.resolve("runtime/release").readText()) { "the runtime lacks jdk.accessibility, and Windows with the Java Access Bridge enabled would not start the app" }
        config.writeText(options("-XX:SharedArchiveFile=\$APPDIR\\networkhelm.jsa"))
    }
}

tasks.register<Exec>("packageInnoSetup") {
    group = "compose desktop"
    description = "Builds the Windows installer with Inno Setup"
    dependsOn(appCds)
    val compiler = innoSetup
    val image = layout.buildDirectory.dir("compose/binaries/main/app/NetworkHelm").get().asFile
    val output = layout.buildDirectory.dir("compose/binaries/main/inno").get().asFile
    val version = rootProject.extra["appVersionName"] as String
    val suffix = flavor
    inputs.dir(image)
    inputs.dir("installer")
    outputs.dir(output)
    executable = compiler ?: "ISCC.exe"
    args("/Q", "/DAppVersion=${version.substringBefore('-')}", "/DAppVersionName=$version", "/DAppImage=$image", "/DOutputDir=$output")
    if (legacy) args("/DLegacy=1")
    if (arm) args("/DArm64=1")
    args(file("installer/NetworkHelm.iss"))
    doFirst { check(compiler != null) { "Inno Setup 6 is not installed: set INNO_SETUP to its ISCC.exe" } }
    doLast { output.resolve("NetworkHelm-$version$suffix-setup.exe").copyTo(output.resolve("NetworkHelm-$version$suffix-portable.exe"), overwrite = true) }
}

val webAppResources = tasks.register<Sync>("webAppResources") {
    from(project(":app:webApp").layout.buildDirectory.dir("bundle"))
    into(layout.buildDirectory.dir("appResources/common/web"))
    dependsOn(":app:webApp:webAppBundle")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { mustRunAfter(webAppResources) }
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach { dependsOn(webAppResources) }

val developerIdIdentity = providers.environmentVariable("APPLE_DEVELOPER_ID_IDENTITY").orNull?.takeIf { it.isNotBlank() }
// -PmacAppStore leaves the image unsigned: tools/release/mac_app_store.py signs it with Apple Distribution, which Compose cannot name
val macAppStore = providers.gradleProperty("macAppStore").map { it.isEmpty() || it.toBoolean() }.getOrElse(false)
val desktopVersion = (rootProject.extra["appVersionName"] as String).substringBefore('-')

// the sandbox quarantines a library unpacked to a temporary file and dlopen hangs on it, so the store build loads them from the app
val nativeJars = configurations.runtimeClasspath.map { classpath ->
    classpath.incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && it.group in setOf("net.java.dev.jna", "org.jetbrains.pty4j", "io.github.dokar3") }
    }.files
}
val macAppStoreNatives = tasks.register<Sync>("macAppStoreNatives") {
    from(nativeJars.map { jars -> jars.map { zipTree(it) } }) {
        include("com/sun/jna/darwin-${if (arm) "aarch64" else "x86-64"}/libjnidispatch.jnilib", "jni/macos_${if (arm) "aarch64" else "x64"}/libquickjs.dylib")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    from(nativeJars.map { jars -> jars.map { zipTree(it) } }) {
        include("resources/com/pty4j/native/darwin/libpty.dylib")
        eachFile { path = "darwin/$name" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("appResources/macos"))
}
if (macAppStore) tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(macAppStoreNatives) }

compose.desktop {
    application {
        mainClass = "com.vasmarfas.networkhelm.MainKt"
        javaHome = javaToolchains.launcherFor(jetBrainsRuntime).get().metadata.installationPath.asFile.absolutePath
        // --enable-native-access: JDK 25 warns at the first native call of each library, JNA and Skia among them
        jvmArgs("-Xmx1g", "-XX:ParallelGCThreads=4", "-XX:ConcGCThreads=1", "-XX:G1PeriodicGCInterval=15000", "-XX:MaxHeapFreeRatio=30", "-XX:MinHeapFreeRatio=10", "--enable-native-access=ALL-UNNAMED")
        // Windows 7 and 8 have no Direct3D 12; Skia draws in memory and Java2D puts the picture on the window, no OpenGL driver involved
        if (legacy) jvmArgs("-Dskiko.renderApi=SOFTWARE_COMPAT")
        if (macAppStore) {
            jvmArgs(
                "-Djna.boot.library.path=\$APPDIR/resources",
                "-Djna.nounpack=true",
                "-Dcom.dokar.quickjs.library.path=\$APPDIR/resources",
                "-Dpty4j.preferred.native.folder=\$APPDIR/resources",
            )
        }

        nativeDistributions {
            // JNA of KSafe needs sun.misc.Unsafe; Netty and logback of the gateway refer to JNDI and JFR; the Java Access Bridge of Windows loads jdk.accessibility at the first window
            modules("jdk.unsupported", "java.naming", "jdk.jfr", "jdk.accessibility")
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "NetworkHelm"
            packageVersion = desktopVersion
            vendor = "vasmarfas"
            description = "NetworkHelm, a client for MikroTik RouterOS and OpenWrt routers"
            licenseFile.set(rootProject.file("LICENSE"))

            windows {
                menuGroup = "NetworkHelm"
                shortcut = true
                perUserInstall = true
                iconFile.set(project.file("icons/networkhelm.ico"))
                upgradeUuid = "11C055CB-1DC3-4CC0-B7F4-A167C93DEDD5"
            }
            macOS {
                bundleID = "com.vasmarfas.networkhelm"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("icons/networkhelm.icns"))
                packageBuildVersion = rootProject.extra["appVersionCode"].toString()
                entitlementsFile.set(project.file("macos/entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>NetworkHelm connects to routers on your network and listens for their discovery announcements.</string>
                    """.trimIndent()
                }
                if (macAppStore) {
                    appStore = true
                    appCategory = "public.app-category.utilities"
                } else if (developerIdIdentity != null) {
                    signing {
                        sign.set(true)
                        identity.set(developerIdIdentity)
                    }
                    val appleId = System.getenv("APPLE_ID")
                    if (!appleId.isNullOrBlank()) {
                        notarization {
                            appleID.set(appleId)
                            password.set(System.getenv("APPLE_ID_PASSWORD"))
                            teamID.set(System.getenv("APPLE_TEAM_ID"))
                        }
                    }
                }
            }
            linux {
                iconFile.set(project.file("icons/networkhelm.png"))
                packageName = "networkhelm"
                menuGroup = "Network"
                rpmLicenseType = "PolyForm-Noncommercial-1.0.0"
            }
        }
    }
}
