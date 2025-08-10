import org.gradle.internal.os.OperatingSystem

plugins {
  id("module-conventions")
  id("java-library")
  id("maven-publish")
}

enum class Variant(val os: String, val arch: String) {
  MacosX64("macos", "x64"),
  MacosArm64("macos", "arm64"),
  LinuxX64("linux", "x64"),
  LinuxArm64("linux", "arm64"),
  WindowsX64("windows", "x64"),
  WindowsArm64("windows", "arm64");

  val sourceSetName = "${name}Main"
  val variantName = name

  fun resourcesDirectory(layout: ProjectLayout) =
      layout.buildDirectory.dir("natives/${variantName}")

  companion object {
    fun fromOsAndArch(os: String, arch: String): Variant {
      val correctedArch =
          when (arch) {
            "x86_64" -> "x64"
            "aarch64" -> "arm64"
            else -> arch
          }
      val correctedOs =
          when {
            os.lowercase().startsWith("mac") -> "macos"
            os.lowercase().startsWith("lin") -> "linux"
            os.lowercase().startsWith("win") -> "windows"
            else -> os
          }
      return values().firstOrNull { it.os == correctedOs && it.arch == correctedArch }
          ?: throw IllegalArgumentException("Unsupported OS/Arch combination: $os/$arch")
    }

    val localDevVariant
      get() = fromOsAndArch(OperatingSystem.current().name, System.getProperty("os.arch"))
  }
}

sourceSets {
  for (variant in Variant.values()) {
    create(variant.sourceSetName) { resources.srcDir(variant.resourcesDirectory(layout)) }
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

fun getLocalDevPreset(): String {
  val os = OperatingSystem.current()
  return project.findProperty("cmake.preset") as String?
<<<<<<< Updated upstream
    ?: when {
      os.isWindows -> "windows-vulkan"
      os.isLinux -> "linux-vulkan"
      os.isMacOsX -> "macos-metal"
      else -> throw GradleException("Unsupported operating system")
    }
||||||| Stash base
    ?: when {
      os.isWindows -> "windows-vulkan"
      os.isLinux -> "linux-vulkan"
      os.isMacOsX -> "macos-vulkan"
      else -> throw GradleException("Unsupported operating system")
    }
=======
      ?: when {
        os.isWindows -> "windows-vulkan"
        os.isLinux -> "linux-vulkan"
        os.isMacOsX -> "macos-metal"
        else -> throw GradleException("Unsupported operating system")
      }
>>>>>>> Stashed changes
}

tasks.register<Exec>("configureCMake") {
  val preset = getLocalDevPreset()

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
  dependsOn("configureCMake")
  val preset = getLocalDevPreset()

  val buildDir = layout.buildDirectory.dir("cmake/${preset}").get().asFile
  workingDir = buildDir

  inputs.files(fileTree("src/main/cpp"))
  inputs.dir(layout.buildDirectory.dir("generated/simplejni-headers"))
  inputs.file(buildDir.resolve("CMakeCache.txt"))

  outputs.file(layout.buildDirectory.file("lib/main/shared/libmaplibre-jni.so"))
  outputs.file(layout.buildDirectory.file("lib/main/shared/libmaplibre-jni.dylib"))
  outputs.file(layout.buildDirectory.file("lib/main/shared/maplibre-jni.dll"))
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
  dependsOn("buildNative")

  val currentVariant = Variant.localDevVariant
  val os = OperatingSystem.current()
  val nativeLibraryName =
      when {
        os.isWindows -> "maplibre-jni.dll"
        os.isMacOsX -> "libmaplibre-jni.dylib"
        os.isLinux -> "libmaplibre-jni.so"
        else -> error("Unsupported operating system: ${os.name}")
      }

  from(layout.buildDirectory.file("lib/main/shared/$nativeLibraryName"))
  into(currentVariant.resourcesDirectory(layout))

  doFirst {
    println("Copying native library for variant: $currentVariant")
    println("From: ${layout.buildDirectory.get().asFile}/lib/main/shared/$nativeLibraryName")
    println("To: ${currentVariant.resourcesDirectory(layout)}")
  }
}

tasks.register<Delete>("cleanNative") {
  delete(layout.buildDirectory.dir("cmake"))
  delete(layout.buildDirectory.dir("lib"))
  delete(layout.buildDirectory.dir("_deps"))
  delete(layout.buildDirectory.dir("natives"))
  delete(layout.buildDirectory.dir("generated/simplejni-headers"))
}

tasks.named("clean") { dependsOn("cleanNative") }

tasks.named("build") {
  dependsOn("buildNative")
  dependsOn("copyNativeToResources")
}

afterEvaluate {
  val currentVariant = Variant.localDevVariant
  val capitalizedVariant = currentVariant.variantName.replaceFirstChar { it.uppercase() }
  tasks
      .matching { it.name == "process${capitalizedVariant}Resources" }
      .configureEach { mustRunAfter("copyNativeToResources") }
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
