import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.application.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
  id(libs.plugins.spmForKmp.get().pluginId)
}

android {
  namespace = "org.maplibre.compose.demoapp"

  defaultConfig {
    applicationId = "org.maplibre.compose.demoapp"
    minSdk = project.properties["androidMinSdk"]!!.toString().toInt()
    compileSdk = project.properties["androidCompileSdk"]!!.toString().toInt()
    targetSdk = project.properties["androidTargetSdk"]!!.toString().toInt()
    versionCode = 1
    versionName = project.version.toString()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  buildTypes { getByName("release") { isMinifyEnabled = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  testOptions { animationsDisabled = true }
}

val desktopHostPlatform = DesktopHostPlatform.current()

kotlin {
  jvmToolchain(properties["jvmToolchain"]!!.toString().toInt())

  androidTarget {
    compilerOptions { jvmTarget = project.getAndroidJvmTarget() }
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework {
      baseName = "DemoApp"
      isStatic = true
    }
    it.configureSpmMaplibre(project)
  }

  jvm("desktop") { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js(IR) {
    browser { commonWebpackConfig { outputFileName = "app.js" } }
    binaries.executable()
  }

  applyDefaultHierarchyTemplate()

  compilerOptions {
    // KLIB resolver: The same 'unique_name=annotation_commonMain' found in more than one library
    allWarningsAsErrors = false
    freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
  }

  sourceSets {
    val desktopMain by getting

    all { languageSettings { optIn("androidx.compose.material3.ExperimentalMaterial3Api") } }

    commonMain.dependencies {
      implementation(libs.jetbrains.compose.components.resources)
      implementation(libs.jetbrains.compose.foundation)
      implementation(libs.jetbrains.compose.material3)
      implementation(libs.jetbrains.compose.runtime)
      implementation(libs.jetbrains.compose.ui)
      implementation(libs.jetbrains.compose.material.iconsExtended)
      implementation(libs.androidx.navigation.compose)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.contentNegotiation)
      implementation(libs.ktor.serialization.kotlinxJson)
      implementation(libs.spatialk.geojson)

      // We exclude the android sdk here so we can select a variant via gradle property.
      // See androidMain below.
      implementation(project(":lib:maplibre-compose")) {
        exclude(group = "org.maplibre.gl", module = "android-sdk")
      }
      implementation(project(":lib:maplibre-compose-material3")) {
        exclude(group = "org.maplibre.gl", module = "android-sdk")
      }
    }

    val nonAndroidShared by creating { dependsOn(commonMain.get()) }

    val androidIosShared by creating { dependsOn(commonMain.get()) }

    // Platforms backed by MapLibre Native, which is where the offline API exists. Mirrors the
    // library's own maplibreNativeMain source set.
    val maplibreNativeShared by creating { dependsOn(commonMain.get()) }

    val desktopJsShared by creating { dependsOn(commonMain.get()) }

    androidMain {
      dependsOn(androidIosShared)
      dependsOn(maplibreNativeShared)
      dependencies {
        implementation(libs.jetbrains.compose.ui.tooling)
        implementation(libs.androidx.activity.compose)
        implementation(libs.kotlinx.coroutines.android)
        implementation(libs.ktor.client.okhttp)
        implementation(libs.accompanist.permissions)

        implementation(project(":lib:maplibre-compose-gms")) {
          exclude(group = "org.maplibre.gl", module = "android-sdk")
        }

        project.properties["demoAppMaplibreAndroidFlavor"].let { flavor ->
          when (flavor) {
            null,
            "default" -> implementation(libs.maplibre.android)
            "opengl" -> implementation(libs.maplibre.androidOpenGL)
            "vulkan" -> implementation(libs.maplibre.androidVulkan)
            "debug" -> implementation(libs.maplibre.androidDebug)
            else -> error("Unknown maplibre android flavor: $flavor")
          }
        }
      }
    }

    iosMain {
      dependsOn(androidIosShared)
      dependsOn(maplibreNativeShared)
      dependsOn(nonAndroidShared)
      dependencies { implementation(libs.ktor.client.darwin) }
    }

    desktopMain.apply {
      dependsOn(maplibreNativeShared)
      dependsOn(nonAndroidShared)
      dependsOn(desktopJsShared)
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
        implementation(libs.ktor.client.okhttp)
        runtimeOnly(desktopHostPlatform.runtimeDependency(libs.versions.maplibre.nativeFfi.get()))

        // LWJGL resolves its natives from the classpath, so the application picks
        // the pair matching its host exactly as it does for the FFI runtime.
        // `variantOf` is not available in a KMP source-set dependency block, so
        // these are spelled out.
        val lwjglVersion = libs.versions.lwjgl.get()
        val lwjglNatives = desktopHostPlatform.lwjglNativesClassifier
        runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
        runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")
      }
    }

    jsMain {
      dependsOn(nonAndroidShared)
      dependsOn(desktopJsShared)
      dependencies {
        implementation(libs.jetbrains.compose.html.core)
        implementation(libs.ktor.client.js)
      }
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))

      implementation(libs.jetbrains.compose.ui.test)
    }

    androidUnitTest.dependencies { implementation(compose.desktop.currentOs) }

    androidInstrumentedTest.dependencies {
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.demoapp.generated" }

composeCompiler { reportsDestination = layout.buildDirectory.dir("compose/reports") }

compose.desktop {
  application {
    mainClass = "org.maplibre.compose.demoapp.MainKt"
    jvmArgs += NATIVE_ACCESS_JVM_ARGS

    nativeDistributions {
      // jpackage runs jlink against this JDK, so it decides the Java version inside the installed
      // application. Without it the packaged app takes whatever JDK Gradle happens to run on,
      // which can be older than the 24 the MapLibre Native FFI binding requires — a mismatch that
      // only appears once a user installs and launches it.
      javaHome =
        javaToolchains
          .launcherFor {
            languageVersion.set(
              JavaLanguageVersion.of(properties["jvmToolchain"]!!.toString().toInt())
            )
          }
          .get()
          .metadata
          .installationPath
          .asFile
          .absolutePath

      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "org.maplibre.compose.demoapp"
      // https://youtrack.jetbrains.com/issue/CMP-2360
      // packageVersion = project.ext["base_tag"].toString().replace("v", "")
      packageVersion = "1.0.0"
    }
  }
}
