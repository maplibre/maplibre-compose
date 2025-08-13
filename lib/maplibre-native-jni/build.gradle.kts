import org.gradle.internal.os.OperatingSystem

plugins {
  id("module-conventions")
  id("java-library")
  id("maven-publish")
}

enum class Variant(
  val os: String,
  val arch: String,
  val renderer: String,
  val sharedLibraryName: String,
) {
  MacosAmd64Metal("macos", "amd64", "metal", "libmaplibre-jni.dylib"),
  MacosAarch64Metal("macos", "aarch64", "metal", "libmaplibre-jni.dylib"),
  MacosAmd64Vulkan("macos", "amd64", "vulkan", "libmaplibre-jni.dylib"),
  MacosAarch64Vulkan("macos", "aarch64", "vulkan", "libmaplibre-jni.dylib"),
  LinuxAmd64OpenGl("linux", "amd64", "opengl", "libmaplibre-jni.so"),
  LinuxAarch64OpenGl("linux", "aarch64", "opengl", "libmaplibre-jni.so"),
  LinuxAmd64Vulkan("linux", "amd64", "vulkan", "libmaplibre-jni.so"),
  LinuxAarch64Vulkan("linux", "aarch64", "vulkan", "libmaplibre-jni.so"),
  WindowsAmd64OpenGl("windows", "amd64", "opengl", "maplibre-jni.dll"),
  WindowsAarch64OpenGl("windows", "aarch64", "opengl", "maplibre-jni.dll"),
  WindowsAmd64Vulkan("windows", "amd64", "vulkan", "maplibre-jni.dll"),
  WindowsAarch64Vulkan("windows", "aarch64", "vulkan", "maplibre-jni.dll");

  val sourceSetName = "${name}Main"
  val cmakePreset = "$os-$renderer"

  fun sharedLibraryFromFile(layout: ProjectLayout) =
    layout.buildDirectory.file("lib/main/shared/$sharedLibraryName")

  fun sharedLibraryToDirectory(layout: ProjectLayout) =
    layout.buildDirectory.dir("natives/${os}/${arch}/${renderer}")

  companion object {
    private fun find(os: String, arch: String, renderer: String? = null) =
      Variant.values().firstOrNull {
        it.os == os && it.arch == arch && (renderer == null || it.renderer == renderer)
      } ?: error("Unsupported combination: ${os}/${arch}/${renderer}")

    fun current(project: Project) =
      find(
        os =
          when (OperatingSystem.current()) {
            OperatingSystem.MAC_OS -> "macos"
            OperatingSystem.LINUX -> "linux"
            OperatingSystem.WINDOWS -> "windows"
            else -> error("Unsupported operating system: ${OperatingSystem.current()}")
          },
        arch = System.getProperty("os.arch"),
        renderer = project.findProperty("desktopRenderer")?.toString(),
      )
  }
}

val configureForPublishing = project.findProperty("configureForPublishing")?.toString() == "true"

sourceSets {
  for (variant in Variant.values()) {
    create(variant.sourceSetName) { resources.srcDir(layout.buildDirectory.dir("natives")) }
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

if (configureForPublishing) {
  tasks.register("validateAllNatives") {
    group = "verification"

    doLast {
      val missing = mutableListOf<String>()
      for (variant in Variant.values()) {
        val file =
          variant.sharedLibraryToDirectory(layout).get().asFile.resolve(variant.sharedLibraryName)
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
} else {
  tasks.register<Exec>("configureCMake") {
    group = "build"
    val preset = Variant.current(project).cmakePreset

    // Use preset-specific subdirectory to avoid rebuilding when switching presets
    val buildDir = layout.buildDirectory.dir("cmake/${preset}").get().asFile
    val simplejniHeadersDir = layout.buildDirectory.dir("generated/simplejni-headers").get().asFile

    inputs.file("CMakeLists.txt")
    inputs.file("CMakePresets.json")
    inputs.dir("src/main/cpp")
    inputs.dir(simplejniHeadersDir)

    outputs.dir(buildDir)
    outputs.file(buildDir.resolve("CMakeCache.txt"))

    doFirst { buildDir.mkdirs() }

    workingDir = buildDir
    commandLine(listOf("cmake", "--preset", preset, projectDir.absolutePath))

    doLast {
      // copy compile_commands.json to a location that clangd can find
      val compileCommandsSrc = buildDir.resolve("compile_commands.json")
      val compileCommandsDst = layout.buildDirectory.get().asFile.resolve("compile_commands.json")
      if (compileCommandsSrc.exists()) {
        compileCommandsSrc.copyTo(compileCommandsDst, overwrite = true)
      }
    }
  }

  tasks.register<Exec>("buildNative") {
    group = "build"

    dependsOn("configureCMake")
    val variant = Variant.current(project)
    val preset = variant.cmakePreset

    val buildDir = layout.buildDirectory.dir("cmake/${preset}").get().asFile
    workingDir = buildDir

    inputs.files(fileTree("src/main/cpp"))
    inputs.dir(layout.buildDirectory.dir("generated/simplejni-headers"))
    inputs.file(buildDir.resolve("CMakeCache.txt"))

    outputs.file(variant.sharedLibraryFromFile(layout))
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
    val fromFile = variant.sharedLibraryFromFile(layout)
    val intoDirectory = variant.sharedLibraryToDirectory(layout)

    from(fromFile)
    into(intoDirectory)

    doFirst {
      println("Copying native library for $variant")
      println("From: ${fromFile.get().asFile.absolutePath}")
      println("To: ${intoDirectory.get().asFile.absolutePath}")
    }
  }

  tasks.named("build") {
    dependsOn("buildNative")
    dependsOn("copyNativeToResources")
  }

  afterEvaluate {
    tasks
      .matching { it.name == "process${Variant.current(project).name}Resources" }
      .configureEach { mustRunAfter("copyNativeToResources") }
  }
}
