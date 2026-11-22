import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "com.vasmarfas.networkhelm"
version = rootProject.extra["appVersionName"] as String

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "com.vasmarfas.networkhelm.ApplicationKt"
}

dependencies {
    implementation(platform("io.netty:netty-bom:4.2.19.Final"))
    api(project(":core"))
    implementation(project(":miknative"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    // the gateway serves HTTPS over TCP: native QUIC of HTTP/3 (five platforms, 11 MB of the jar) stays out
    implementation(libs.ktor.serverNetty) {
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation(libs.ktor.serverWebsockets)
    implementation(libs.ktor.serverCompression)
    implementation(libs.ktor.networkTlsCertificates)
    implementation(libs.kotlinx.serializationJson)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.kotlin.testJunit)
}

ktor {
    fatJar {
        archiveFileName.set("networkhelm-gateway.jar")
    }
}

tasks.named<Jar>("shadowJar") {
    from(project(":app:webApp").layout.buildDirectory.dir("bundle")) { into("web") }
    dependsOn(":app:webApp:webAppBundle")
}

// jdeps finds the modules of the jar, but not the TLS curves, the character sets and SASL
val gatewayModules = "java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.security.jgss," +
    "java.security.sasl,java.xml,jdk.charsets,jdk.crypto.ec,jdk.jfr,jdk.unsupported"
val windows = System.getProperty("os.name").startsWith("Windows")
val processor = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
val platform = "${if (windows) "windows" else if (System.getProperty("os.name").startsWith("Mac")) "macos" else "linux"}-$processor"

val gatewayRuntime = tasks.register<Exec>("gatewayRuntime") {
    group = "distribution"
    description = "Makes the Java runtime of the gateway with jlink"
    val runtime = layout.buildDirectory.dir("gateway/runtime")
    val jdk = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }
    val jlink = if (windows) "bin/jlink.exe" else "bin/jlink"
    inputs.property("modules", gatewayModules)
    outputs.dir(runtime)
    args("--add-modules", gatewayModules, "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6", "--output", runtime.get().asFile.path)
    // jlink refuses a folder that exists
    doFirst {
        runtime.get().asFile.deleteRecursively()
        executable = jdk.get().resolve(jlink).path
    }
}

val gatewayImage = tasks.register<Sync>("gatewayImage") {
    group = "distribution"
    description = "Puts the runtime, the jar and the launchers of the gateway in one folder"
    dependsOn(gatewayRuntime, "buildFatJar")
    into(layout.buildDirectory.dir("gateway/image/NetworkHelmGateway"))
    from(layout.buildDirectory.dir("gateway/runtime")) { into("runtime") }
    from(layout.buildDirectory.file("libs/networkhelm-gateway.jar"))
    from("packaging") {
        exclude("docker-entrypoint.sh")
        exclude(if (windows) listOf("*.sh", "*.service") else listOf("*.bat"))
    }
}

fun AbstractArchiveTask.packGateway() {
    group = "distribution"
    description = "Packs the gateway with its own Java as the archive of this system"
    dependsOn(gatewayImage)
    from(layout.buildDirectory.dir("gateway/image"))
    filesMatching(listOf("**/*.sh", "**/runtime/bin/*", "**/runtime/lib/jspawnhelper")) { permissions { unix("rwxr-xr-x") } }
    archiveFileName.set("NetworkHelm-gateway-${rootProject.extra["appVersionName"]}-$platform.${if (windows) "zip" else "tar.gz"}")
    destinationDirectory.set(layout.buildDirectory.dir("gateway"))
}

if (windows) {
    tasks.register<Zip>("gatewayArchive") { packGateway() }
} else {
    tasks.register<Tar>("gatewayArchive") {
        packGateway()
        compression = Compression.GZIP
    }
}

tasks.named<JavaExec>("run") {
    environment("NETWORKHELM_GATEWAY_WEB", project(":app:webApp").layout.buildDirectory.dir("bundle").get().asFile.path)
    dependsOn(":app:webApp:webAppBundle")
}

val labProperties = rootProject.layout.projectDirectory.file("lab.properties").asFile
tasks.test {
    systemProperty("networkhelm.lab", labProperties.path)
    if (labProperties.exists()) doNotTrackState("lab tests depend on live routers")
}
