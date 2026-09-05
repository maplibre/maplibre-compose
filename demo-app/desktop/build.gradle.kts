import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
}

val desktopHostPlatform = DesktopHostPlatform.current()
val desktopRenderBackend =
  desktopHostPlatform.selectedRenderBackend(
    providers.gradleProperty("maplibre.desktop.backend").orNull
  )

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  implementation(project(":demo-app:common"))
  implementation(project(":lib:maplibre-compose"))
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutines.swing)

  runtimeOnly(project(":lib:${desktopHostPlatform.runtimeArtifactId(desktopRenderBackend)}"))
}

compose.desktop {
  application {
    mainClass = "org.maplibre.compose.demoapp.MainKt"
    jvmArgs += NATIVE_ACCESS_JVM_ARGS

    nativeDistributions {
      // Package the configured toolchain, even when Gradle runs on an older JDK.
      javaHome =
        javaToolchains
          .launcherFor {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
          }
          .get()
          .metadata
          .installationPath
          .asFile
          .absolutePath

      // jpackage ships DMG and MSI. Linux uses appimagetool via
      // `mise run build:desktop-app`: Compose TargetFormat.AppImage is an
      // unpacked directory, not a .AppImage file, and TargetFormat.Deb needs
      // fakeroot.
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
      packageName = "org.maplibre.compose.demoapp"
      // https://youtrack.jetbrains.com/issue/CMP-2360
      packageVersion = "1.0.0"

      macOS {
        // jpackage signs with the hardened runtime. Core Location ignores authorization
        // requests unless that runtime also has the location entitlement.
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
    }
  }
}
