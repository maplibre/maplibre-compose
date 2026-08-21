import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
}

val desktopHostPlatform = DesktopHostPlatform.current()

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  implementation(project(":demo-app:common"))
  implementation(project(":lib:maplibre-compose"))
  implementation(platform(libs.lwjgl.bom))
  implementation(libs.composeGlfw)

  runtimeOnly(desktopHostPlatform.composeGlfwRuntimeDependency(libs.versions.composeGlfw.get()))
  runtimeOnly(project(":lib:${desktopHostPlatform.defaultRuntimeArtifactId}"))
}

/**
 * Packages the compose-glfw host without `compose.desktop.currentOs`, so this module stays off the
 * AWT runtime classpath. The module exists so its `MainDispatcherFactory`, which outranks
 * `kotlinx-coroutines-swing`, never reaches the AWT demo.
 *
 * GLFW must own AppKit's first thread on macOS, and the FFI binding is refused native access
 * without `--enable-native-access`.
 */
compose.desktop {
  application {
    mainClass = "org.maplibre.compose.glfw.MainKt"
    jvmArgs += NATIVE_ACCESS_JVM_ARGS
    if (System.getProperty("os.name").lowercase().startsWith("mac")) {
      jvmArgs += "-XstartOnFirstThread"
    }

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
      packageName = "org.maplibre.compose.demoapp.glfw"
      // https://youtrack.jetbrains.com/issue/CMP-2360
      // packageVersion = providers.gradleProperty("maplibreReleaseVersion").get()
      packageVersion = "1.0.0"

      macOS {
        val entitlements = project(":demo-app:desktop").file("entitlements.plist")
        entitlementsFile.set(entitlements)
        runtimeEntitlementsFile.set(entitlements)
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
