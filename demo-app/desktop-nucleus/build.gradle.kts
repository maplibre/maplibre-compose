import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.reload.gradle.ComposeHotRun

plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.nucleus.get().pluginId)
}

val desktopHostPlatform = DesktopHostPlatform.current()
val desktopRenderBackend =
  desktopHostPlatform.selectedRenderBackend(
    providers.gradleProperty("maplibre.desktop.backend").orNull,
  )

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  implementation(project(":demo-app:common"))
  implementation(project(":lib:maplibre-compose"))
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.nucleus.application)
  implementation(libs.nucleus.decoratedWindowTao)

  runtimeOnly(compose.desktop.currentOs)
  runtimeOnly(project(":lib:${desktopHostPlatform.runtimeArtifactId(desktopRenderBackend)}"))
}

nucleus.application {
  mainClass = "org.maplibre.compose.nucleus.MainKt"
  // Nucleus ignores the Kotlin toolchain and defaults to the Gradle daemon's JDK.
  // https://github.com/NucleusFramework/Nucleus/issues/830
  javaHome =
    javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
  if (desktopHostPlatform == DesktopHostPlatform.MacosArm64) {
    // Tao records the wrong dispatcher thread without this flag on ordinary launches.
    // https://github.com/NucleusFramework/Nucleus/issues/829
    jvmArgs += "-XstartOnFirstThread"
    // Packaging re-signs LWJGL dylibs, changing the bytes checked against their JAR hashes.
    // https://github.com/maplibre/maplibre-compose/issues/1498
    jvmArgs += "-Dorg.lwjgl.util.NoHashChecks=true"
  }

  nativeDistributions {
    targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.AppImage)
    outputBaseDir.set(layout.buildDirectory.dir("packages"))
    packageName = "MapLibreCompose"
    appName = "MapLibre Compose"
    packageVersion = project.version.toString()
    description = "MapLibre Compose demo"
    vendor = "MapLibre"
    homepage = "https://maplibre.org/maplibre-compose/"

    macOS {
      bundleID = "org.maplibre.compose.demoapp"
      // jpackage requires a positive major version; map 0.x.y to 1.x.y for the bundle.
      // https://youtrack.jetbrains.com/issue/CMP-2360
      val version = project.version.toString()
      packageVersion =
        version.replaceBefore('.', (version.substringBefore('.').toInt() + 1).toString())
      entitlementsFile.set(file("entitlements.plist"))
      runtimeEntitlementsFile.set(file("entitlements.plist"))
      infoPlist {
        extraKeysRawXml =
          """
          <key>NSLocationWhenInUseUsageDescription</key>
          <string>The My location demo draws your position on the map.</string>
          <key>NSLocationUsageDescription</key>
          <string>The My location demo draws your position on the map.</string>
          """
            .trimIndent()
      }
    }

    linux {
      packageName = "maplibre-compose"
      iconFile.set(
        rootProject.file(
          "demo-app/ios/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png",
        ),
      )
    }
  }
}

// Actions artifacts discard executable permissions; tar preserves the AppImage's launchability.
val archiveAppImage =
  tasks.register<Tar>("archiveAppImage") {
    dependsOn("packageAppImage")
    from(layout.buildDirectory.dir("packages/main/AppImage")) {
      include("*.AppImage")
      filePermissions { unix("rwxr-xr-x") }
    }
    compression = Compression.GZIP
    archiveFileName.set("MapLibreCompose-${project.version}.AppImage.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("packages/main/AppImage"))
  }

tasks.register("packageDemo") {
  dependsOn("packageDistributionForCurrentOS")
  if (desktopHostPlatform in setOf(DesktopHostPlatform.LinuxX64, DesktopHostPlatform.LinuxArm64))
    dependsOn(archiveAppImage)
}

tasks.withType<ComposeHotRun>().configureEach {
  // Nucleus enables native access for ordinary launches; hot reload needs it explicitly.
  jvmArgs(NATIVE_ACCESS_JVM_ARGS)
}
