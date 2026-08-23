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
      // jpackage runs jlink against this JDK, so it decides the Java version inside the installed
      // application; without it the packaged app takes whatever JDK Gradle runs on, which can be
      // older than the 24 the MapLibre Native FFI binding requires.
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

      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "org.maplibre.compose.demoapp"
      // https://youtrack.jetbrains.com/issue/CMP-2360
      // packageVersion = providers.gradleProperty("maplibreReleaseVersion").get()
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
            <string>Example</string>
            <key>NSLocationUsageDescription</key>
            <string>Example</string>
            """
              .trimIndent()
        }
      }
    }
  }
}
