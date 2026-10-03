import org.jetbrains.kotlin.gradle.ExperimentalJsTestDsl

plugins {
  id("module-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
}

val benchmarkBuildInfo =
  tasks.register<GenerateBenchmarkBuildInfo>("generateBenchmarkBuildInfo") {
    repository.set(rootProject.layout.projectDirectory)
    dependencyVersions.putAll(
      mapOf(
        "classic_android" to libs.versions.maplibre.android.get(),
        "classic_ios" to libs.versions.maplibre.ios.get(),
        "native_ffi" to libs.versions.maplibre.nativeFfi.get(),
        "gl_js" to libs.versions.maplibre.js.get(),
      )
    )
    fixtureSources.from(
      rootProject.layout.projectDirectory.file("benchmarks/fixtures/manifest.json"),
      rootProject.layout.projectDirectory.file("benchmarks/prepare_fixtures.py"),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/benchmarkBuildInfo"))
  }

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  android { namespace = "org.maplibre.compose.benchmark" }
  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }
  listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
    val sdk =
      when (target.name) {
        "iosArm64" -> "iphoneos"
        "iosSimulatorArm64" -> "iphonesimulator"
        else -> "macosx"
      }
    val platform =
      when (target.name) {
        "iosArm64" -> "ios${libs.versions.apple.benchmarkIosMinimum.get()}"
        "iosSimulatorArm64" -> "ios${libs.versions.apple.benchmarkIosMinimum.get()}-simulator"
        else -> "macos${libs.versions.apple.benchmarkMacosMinimum.get()}"
      }
    val archive = layout.buildDirectory.file("metal-frames/${target.name}/libMetalFrames.a")
    val compile =
      tasks.register<Exec>("compileMetalFrames${target.name.replaceFirstChar(Char::uppercase)}") {
        inputs.files(
          "compile_metal_frames.py",
          "src/nativeInterop/cinterop/MetalFrames.m",
          "src/nativeInterop/cinterop/MetalFrames.h",
        )
        outputs.file(archive)
        commandLine(
          "python3",
          project.file("compile_metal_frames.py"),
          sdk,
          "arm64-apple-$platform",
          archive.get().asFile,
        )
      }
    target.compilations.getByName("main").cinterops.create("MetalFrames") {
      definitionFile.set(project.file("src/nativeInterop/cinterop/MetalFrames.def"))
      includeDirs(project.file("src/nativeInterop/cinterop"))
      extraOpts("-libraryPath", archive.get().asFile.parent)
      tasks.named(interopProcessingTaskName).configure { dependsOn(compile) }
    }
  }
  js {
    useEsModules()
    browser {
      @OptIn(ExperimentalJsTestDsl::class)
      test {
        chromium()
        firefox()
        webkit()
      }
    }
  }
  sourceSets {
    commonMain { kotlin.srcDir(benchmarkBuildInfo.flatMap { it.outputDirectory }) }
    jsTest.dependencies { implementation(npm("mocha", libs.versions.mocha.get())) }
    androidDeviceTest.dependencies { implementation(libs.androidx.test.runner) }
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

configureBrowserTestBundle()
