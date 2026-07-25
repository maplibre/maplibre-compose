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

    val desktopJsShared by creating { dependsOn(commonMain.get()) }

    androidMain {
      dependsOn(androidIosShared)
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
      dependsOn(nonAndroidShared)
      dependencies { implementation(libs.ktor.client.darwin) }
    }

    desktopMain.apply {
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

/**
 * Fails fast when the desktop runtime classpath does not carry exactly one MapLibre Native FFI
 * native runtime matching this host. Getting this wrong otherwise surfaces as an obscure link or
 * backend error at map creation, far from the build that chose it.
 */
val checkDesktopFfiRuntime by tasks.registering {
  group = "verification"
  description = "Checks the desktop MapLibre Native FFI runtime resolves for this host."

  val platform = desktopHostPlatform
  val lwjglVersion = libs.versions.lwjgl.get()
  val runtimeClasspath = configurations.named("desktopRuntimeClasspath")

  doLast {
    val names = runtimeClasspath.get().files.map { it.name }

    val binding = names.filter { it.startsWith("maplibre-native-ffi-jvm") }
    check(binding.size == 1) {
      "Expected exactly one MapLibre Native FFI binding on the desktop runtime classpath, " +
        "found $binding"
    }

    val natives = names.filter { it.contains("maplibre-native-ffi-runtime-") }
    check(natives.size == 1) {
      "Expected exactly one MapLibre Native FFI native runtime, found $natives. " +
        "An application must select a single OS/architecture/backend runtime."
    }
    check(natives.single().contains(platform.nativesClassifier)) {
      "Resolved native runtime ${natives.single()} does not match this host " +
        "(${platform.nativesClassifier}, backend ${platform.renderBackend})"
    }

    // The default Skiko host talks to Vulkan and OpenGL through LWJGL, which loads its natives
    // from the classpath. A missing classifier jar surfaces as an UnsatisfiedLinkError deep in
    // bridge setup rather than as a dependency problem.
    // Matched with the version so that "lwjgl-" does not also match "lwjgl-opengl-".
    for (module in listOf("lwjgl", "lwjgl-opengl")) {
      val prefix = "$module-$lwjglVersion-natives-"
      val jar = names.filter { it.startsWith(prefix) }
      check(jar.size == 1) {
        "Expected exactly one $module natives jar on the desktop runtime classpath, found $jar"
      }
      check(jar.single().contains(platform.lwjglNativesClassifier)) {
        "Resolved ${jar.single()} does not match this host (${platform.lwjglNativesClassifier})"
      }
    }

    logger.lifecycle("Desktop FFI runtime OK: ${binding.single()}, ${natives.single()}")
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.demoapp.generated" }

composeCompiler { reportsDestination = layout.buildDirectory.dir("compose/reports") }

compose.desktop {
  application {
    mainClass = "org.maplibre.compose.demoapp.MainKt"
    jvmArgs += NATIVE_ACCESS_JVM_ARGS

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "org.maplibre.compose.demoapp"
      // https://youtrack.jetbrains.com/issue/CMP-2360
      // packageVersion = project.ext["base_tag"].toString().replace("v", "")
      packageVersion = "1.0.0"
    }
  }
}
