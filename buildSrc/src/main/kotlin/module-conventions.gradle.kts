group = "org.maplibre.compose"

version = providers.gradleProperty("maplibreVersion").get()

val swiftPackageBuilds =
  gradle.sharedServices.registerIfAbsent("swiftPackageBuilds", SwiftPackageBuildService::class) {
    maxParallelUsages = 1
  }

tasks
  .matching { it.name.startsWith("SwiftPackageConfig") }
  .configureEach { usesService(swiftPackageBuilds) }

tasks.withType<AbstractTestTask>().configureEach { failOnNoDiscoveredTests = false }

// Desktop tests may load the MapLibre Native FFI runtime, which needs native access.
tasks.withType<Test>().configureEach {
  if (name.startsWith("desktop")) jvmArgs(NATIVE_ACCESS_JVM_ARGS)
}
