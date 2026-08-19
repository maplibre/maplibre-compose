import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

group = "org.maplibre.compose"

version = providers.gradleProperty("maplibreVersion").get()

// Here rather than in library-conventions so that the demo app modules are covered too, and by
// task rather than by extension so that it does not matter which Kotlin plugin a module applies.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
  compilerOptions { allWarningsAsErrors = true }
}

// Compose's iOS metadata contains duplicate KLIB names. Keep the upstream warning visible without
// making this intermediate compilation fail. https://youtrack.jetbrains.com/issue/CMP-8498
tasks
  .matching { it.name == "compileIosMainKotlinMetadata" }
  .withType<KotlinCompilationTask<*>>()
  .configureEach { compilerOptions { allWarningsAsErrors = false } }

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
  if (name.startsWith("jvm")) jvmArgs(NATIVE_ACCESS_JVM_ARGS)
}
