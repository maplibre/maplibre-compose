@file:Suppress("RedundantUnitExpression")

import com.android.tools.r8.internal.os

plugins {
  id("module-conventions")
  id("java-library")
  id("maven-publish")
  id(libs.plugins.mavenPublish.get().pluginId)
}

enum class Variant(
  val os: String,
  val arch: String,
  val renderer: String,
  // TODO: default true when all/most are publishable
  val requireWhenPublishing: Boolean = false,
) {
  // TODO: enable alternate architectures and renderers
  MacosAmd64Metal("macos", "amd64", "metal"),
  MacosAarch64Metal("macos", "aarch64", "metal", true),
  MacosAmd64Vulkan("macos", "amd64", "vulkan"),
  MacosAarch64Vulkan("macos", "aarch64", "vulkan"),
  LinuxAmd64Opengl("linux", "amd64", "opengl", true),
  LinuxAarch64Opengl("linux", "aarch64", "opengl"),
  LinuxAmd64Vulkan("linux", "amd64", "vulkan"),
  LinuxAarch64Vulkan("linux", "aarch64", "vulkan"),
  WindowsAmd64Opengl("windows", "amd64", "opengl", true),
  WindowsAarch64Opengl("windows", "aarch64", "opengl"),
  WindowsAmd64Vulkan("windows", "amd64", "vulkan"),
  WindowsAarch64Vulkan("windows", "aarch64", "vulkan");

  val sourceSetName = "${name}Main"
  val cmakePreset = "$os-$renderer"

  val sharedLibraryExtension =
    when (os) {
      "macos" -> "dylib"
      "windows" -> "dll"
      else -> "so"
    }

  val sharedLibraryName =
    when (os) {
      "windows" -> "maplibre-jni.${sharedLibraryExtension}"
      else -> "libmaplibre-jni.${sharedLibraryExtension}"
    }

  fun cmakeOutputDirectory(layout: ProjectLayout) =
    layout.buildDirectory.dir("lib/$cmakePreset/shared")

  fun resourcesTargetDirectory(layout: ProjectLayout) =
    layout.buildDirectory.dir("copiedResources/$name/$os/$arch/$renderer")

  fun resourcesSourceDir(layout: ProjectLayout) = layout.buildDirectory.dir("copiedResources/$name")

  companion object {
    private fun find(os: String, arch: String, renderer: String? = null) =
      Variant.values().firstOrNull {
        it.os == os && it.arch == arch && (renderer == null || it.renderer == renderer)
      } ?: error("Unsupported combination: ${os}/${arch}/${renderer}")

    fun current(project: Project): Variant {
      return find(
        os =
          when (val os = System.getProperty("os.name").lowercase()) {
            "mac os x" -> "macos"
            else -> os.split(" ").first()
          },
        arch =
          when (val arch = System.getProperty("os.arch").lowercase()) {
            "x86_64" -> "amd64" // jdk returns x86_64 on macos but amd64 elsewhere
            else -> arch
          },
        renderer = project.findProperty("desktopRenderer")?.toString(),
      )
    }
  }
}

val configureForPublishing = project.findProperty("configureForPublishing")?.toString() == "true"

sourceSets {
  for (variant in Variant.values()) {
    create(variant.sourceSetName) { resources.srcDir(variant.resourcesSourceDir(layout)) }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(properties["jvmToolchain"]!!.toString().toInt()))
  }
  for (variant in Variant.values()) {
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

if (configureForPublishing) {
  // when publishing, we build all variants in CI and copy them to the resources directory
  // so in gradle, we just need to validate that they're present

  tasks.register("validateAllNatives") {
    group = "verification"

    doLast {
      val missing = mutableListOf<String>()
      for (variant in Variant.values().filter { it.requireWhenPublishing }) {
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
    inputs.file(layout.projectDirectory.file("vendor/SimpleJNI/CMakeLists.txt"))
    inputs.file(layout.projectDirectory.file("vendor/maplibre-native/CMakeLists.txt"))
    inputs.file(layout.projectDirectory.file("CMakePresets.json"))
    inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
    inputs.dir(layout.buildDirectory.dir("generated/simplejni-headers"))

    // Use preset-specific subdirectory to avoid rebuilding when switching presets
    val preset = Variant.current(project).cmakePreset
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

    val variant = Variant.current(project)
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

    val variant = Variant.current(project)
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

  Variant.values().forEach { variant ->
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
