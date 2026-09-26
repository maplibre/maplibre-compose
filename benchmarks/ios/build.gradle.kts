plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
}

kotlin {
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    val slice = if (target.name == "iosArm64") "ios-arm64" else "ios-arm64_x86_64-simulator"
    val frameworkDirectory =
      layout.buildDirectory.dir("sdk/MapLibre.xcframework/$slice").get().asFile
    target.compilations.getByName("main").cinterops.create("MapLibre") {
      definitionFile.set(project.file("src/nativeInterop/cinterop/MapLibre.def"))
      compilerOpts("-F$frameworkDirectory")
    }
    target.binaries.framework {
      baseName = "ClassicBenchmark"
      isStatic = true
      linkerOpts("-F$frameworkDirectory", "-framework", "MapLibre")
    }
  }
  sourceSets {
    iosMain.dependencies { implementation(project(":benchmarks:core")) }
  }
}
