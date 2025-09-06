@file:Suppress("RedundantUnitExpression")

plugins {
  id("module-conventions")
  id("java-library")
  id("maven-publish")
  id(libs.plugins.mavenPublish.get().pluginId)
}

val config = Configuration(project)

val DesktopVariant.sourceSetName: String
  get() = "${name}Main"

val DesktopVariant.cmakePreset: String
  get() = "${os}-${renderer}"

val DesktopVariant.sharedLibraryExtension: String
  get() =
    when (os) {
      "macos" -> "dylib"
      "windows" -> "dll"
      else -> "so"
    }

val DesktopVariant.sharedLibraryName: String
  get() =
    when (os) {
      "windows" -> "maplibre-jni.${sharedLibraryExtension}"
      else -> "libmaplibre-jni.${sharedLibraryExtension}"
    }

fun DesktopVariant.cmakeOutputDirectory(layout: ProjectLayout) =
  layout.buildDirectory.dir("lib/$cmakePreset/shared")

fun DesktopVariant.resourcesTargetDirectory(layout: ProjectLayout) =
  layout.buildDirectory.dir("copiedResources/$name/${os}/$arch/$renderer")

fun DesktopVariant.resourcesSourceDir(layout: ProjectLayout) =
  layout.buildDirectory.dir("copiedResources/$name")

sourceSets {
  for (variant in DesktopVariant.currentValues(project)) {
    create(variant.sourceSetName) { resources.srcDir(variant.resourcesSourceDir(layout)) }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(properties["jvmToolchain"]!!.toString().toInt()))
  }
  for (variant in DesktopVariant.currentValues(project)) {
    registerFeature(variant.name) { usingSourceSet(sourceSets[variant.sourceSetName]) }
  }
}

mavenPublishing {
  pom {
    name = "MapLibre Native Bindings (Natives)"
    description = "JNI native libraries for MapLibre Native Bindings."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      setUrl("https://maven.pkg.github.com/maplibre/maplibre-compose")
      credentials {
        username = project.properties["githubUser"]?.toString()
        password = project.properties["githubToken"]?.toString()
      }
    }
  }
}

if (config.shouldConfigureForPublishing) {
  // when publishing, we build all variants in CI and copy them to the resources directory
  // so in gradle, we just need to validate that they're present

  tasks.register("validateAllNatives") {
    group = "verification"

    doLast {
      val missing = mutableListOf<String>()
      for (variant in DesktopVariant.currentValues(project)) {
        val file =
          variant.resourcesTargetDirectory(layout).get().asFile.resolve(variant.sharedLibraryName)
        if (!file.exists()) {
          missing.add("${variant.name}: ${file.absolutePath}")
        }
      }
      if (missing.isNotEmpty()) {
        throw GradleException(
          "Missing native libraries for variants:\n" + missing.joinToString("\n")
        )
      }
    }
  }

  tasks.named("build") { dependsOn("validateAllNatives") }

  Unit // gradle doesn't like if expressions returning things
} else {
  // when not publishing, we build the variant relevant to the current platform

  tasks.register<Exec>("configureCMake") {
    group = "build"

    dependsOn(":lib:maplibre-native-bindings:kspKotlinDesktop")
    inputs.file(layout.projectDirectory.file("CMakeLists.txt"))
    inputs.file(layout.projectDirectory.dir("cmake"))
    inputs.file(layout.projectDirectory.file("vendor/SimpleJNI/CMakeLists.txt"))
    inputs.file(layout.projectDirectory.file("vendor/maplibre-native/CMakeLists.txt"))
    inputs.file(layout.projectDirectory.file("CMakePresets.json"))
    inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
    inputs.dir(layout.buildDirectory.dir("generated/simplejni-headers"))

    // Use preset-specific subdirectory to avoid rebuilding when switching presets
    val preset = DesktopVariant.currentValues(project).first().cmakePreset
    val buildDir = layout.buildDirectory.dir("cmake/${preset}")
    outputs.dir(buildDir)

    doFirst { buildDir.get().asFile.mkdirs() }

    workingDir = buildDir.get().asFile
    commandLine(listOf("cmake", "--preset", preset, projectDir.absolutePath))

    doLast {
      // copy compile_commands.json to a location that clangd can find
      val compileCommandsSrc = buildDir.get().asFile.resolve("compile_commands.json")
      val compileCommandsDst = layout.projectDirectory.asFile.resolve("compile_commands.json")
      if (compileCommandsSrc.exists()) {
        compileCommandsSrc.copyTo(compileCommandsDst, overwrite = true)
      }
    }
  }

  tasks.register<Exec>("buildNative") {
    group = "build"

    dependsOn("configureCMake")
    dependsOn(":lib:maplibre-native-bindings:kspKotlinDesktop")

    val variant = DesktopVariant.currentValues(project).first()
    val preset = variant.cmakePreset
    val buildDir = layout.buildDirectory.dir("cmake/${preset}").get().asFile
    workingDir = buildDir

    inputs.files(fileTree("src/main/cpp"))
    inputs.dir(layout.buildDirectory.dir("generated/simplejni-headers"))
    inputs.dir(buildDir)

    outputs.dir(variant.cmakeOutputDirectory(layout))
    outputs.dir(layout.buildDirectory.dir("lib"))

    commandLine(
      "cmake",
      "--build",
      ".",
      "--config",
      "Release",
      "--parallel",
      Runtime.getRuntime().availableProcessors().toString(),
    )
  }

  tasks.register<Copy>("copyNativeToResources") {
    group = "build"
    dependsOn("buildNative")

    val variant = DesktopVariant.currentValues(project).first()
    val fromDirectory = variant.cmakeOutputDirectory(layout)
    val intoDirectory = variant.resourcesTargetDirectory(layout)

    from(fromDirectory) { include("*.${variant.sharedLibraryExtension}") }
    into(intoDirectory)

    doFirst {
      println("Copying native libraries for $variant")
      println("From: ${fromDirectory.get().asFile.absolutePath}")
      println("To: ${intoDirectory.get().asFile.absolutePath}")
    }
  }

  DesktopVariant.currentValues(project).forEach { variant ->
    tasks.named("process${variant.sourceSetName}Resources") { dependsOn("copyNativeToResources") }
  }

  // poison the publish tasks to ensure we never publish without configureForPublishing set
  tasks
    .matching { it.name.startsWith("publish") }
    .configureEach {
      doFirst {
        throw GradleException("publish tasks are disabled when configureForPublishing is false")
      }
    }

  Unit // gradle doesn't like `if` expressions returning things
}
