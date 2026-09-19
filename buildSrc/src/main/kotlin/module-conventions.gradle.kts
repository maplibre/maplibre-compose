import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

group = "org.maplibre.compose"

version = providers.gradleProperty("maplibreVersion").get()

// Kotlin wrappers bring kotlin-test into production JS dependencies. Its compiler intrinsics
// must match our compiler, even when no test source set requests the newer version.
configurations
  .matching { it.name == "jsMainImplementation" }
  .configureEach {
    project.dependencies.constraints.add(
      name,
      "org.jetbrains.kotlin:kotlin-test:${project.catalogVersion("gradle-kotlin")}",
    )
  }

// Here rather than in library-conventions so that the demo app modules are covered too, and by
// task rather than by extension so that it does not matter which Kotlin plugin a module applies.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
  compilerOptions { allWarningsAsErrors = true }
}

// Unresolved KDoc links otherwise only print `w:` in the docs and publishing jobs.
pluginManager.withPlugin("org.jetbrains.dokka") {
  extensions.configure<DokkaExtension> {
    dokkaPublications.configureEach { failOnWarning.set(true) }
  }
}

// Compose's Apple metadata contains duplicate KLIB names. Keep the upstream warning visible without
// making this intermediate compilation fail. https://youtrack.jetbrains.com/issue/CMP-8498
tasks
  .matching {
    it.name in
      setOf(
        "compileAppleMainKotlinMetadata",
        "compileIosMainKotlinMetadata",
        "compileMacosMainKotlinMetadata",
      )
  }
  .withType<KotlinCompilationTask<*>>()
  .configureEach { compilerOptions { allWarningsAsErrors = false } }

tasks.withType<AbstractTestTask>().configureEach {
  failOnNoDiscoveredTests = false
  // SHORT prints "Type at File:line" and drops the message. CI needs the message.
  testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

// Desktop tests may load the MapLibre Native FFI runtime, which needs native access.
// Robolectric 4.17+ host tests need JDK internals opened for SDK 36+.
tasks.withType<Test>().configureEach {
  if (name.startsWith("jvm")) jvmArgs(NATIVE_ACCESS_JVM_ARGS)
  if (name.contains("AndroidHostTest")) {
    jvmArgs(NATIVE_ACCESS_JVM_ARGS)
    jvmArgs(ROBOLECTRIC_JVM_ARGS)
  }
}
