package org.maplibre.kmp.native.util

import java.io.FileOutputStream
import java.nio.file.Files

internal object SharedLibraryLoader {
  private var loaded = false

  private val os =
    when (val os = System.getProperty("os.name").lowercase()) {
      "mac os x" -> "macos"
      else -> os.split(" ").first()
    }

  private val arch =
    when (val arch = System.getProperty("os.arch").lowercase()) {
      "x86_64" -> "amd64" // jdk returns x86_64 on macos but amd64 elsewhere
      else -> arch
    }

  private val supportedRenderers =
    when (os) {
      "macos" -> listOf("metal", "vulkan")
      else -> listOf("opengl", "vulkan")
    }

  private val libraryNames =
    when {
      os == "windows" && arch == "aarch64" ->
        listOf(
          "icudt74.dll", // ICU data (must load before icuuc/icuini)
          "icuuc74.dll", // ICU common (must load before icuin)
          "icuin74.dll", // ICU internationalization
          "zlib1.dll", // zlib (required by libpng, libwebp, etc.)
          "libpng16.dll", // libpng (depends on zlib)
          "jpeg62.dll", // jpeg (no dependencies)
          "libwebpdecoder.dll", // webp decoder (may depend on zlib, libpng, jpeg)
          "uv.dll", // libuv (no dependencies)
          "libcurl.dll", // libcurl (depends on zlib)
          "maplibre-jni.dll", // main JNI library (depends on all above)
        )
      os == "windows" -> listOf("libmaplibre-jni.dll")
      os == "macos" -> listOf("libmaplibre-jni.dylib")
      else -> listOf("libmaplibre-jni.so")
    }

  private fun getLibraryBasePaths(): List<String> {
    return supportedRenderers.map { renderer -> "$os/$arch/$renderer" }
  }

  @Suppress("UnsafeDynamicallyLoadedCode")
  private fun extractAndLoadLibrary(libraryPath: String) {
    val fileName = libraryPath.substringAfterLast('/')
    val resourcePath = "/$libraryPath"
    val inputStream =
      SharedLibraryLoader::class.java.getResourceAsStream(resourcePath)
        ?: throw UnsatisfiedLinkError("Native library not found in JAR: $resourcePath")

    // Create a temporary file for the native library
    val tempDir = Files.createTempDirectory("maplibre-native-")
    val tempFile = tempDir.resolve(fileName).toFile()
    tempFile.deleteOnExit()

    // Copy the library from JAR to temp file
    inputStream.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }

    // Make the file executable (important for Unix-like systems)
    tempFile.setExecutable(true)

    // Load the library from the temp file
    System.load(tempFile.absolutePath)
    loaded = true
  }

  fun load() {
    if (loaded) return
    val errors = mutableListOf<UnsatisfiedLinkError>()

    getLibraryBasePaths().forEach { basePath ->
      try {
        libraryNames.forEach { fileName -> extractAndLoadLibrary("$basePath/$fileName") }
        return
      } catch (e: UnsatisfiedLinkError) {
        errors.add(e)
      }
    }

    throw errors.firstOrNull { !(it.message?.contains("not found in JAR") ?: false) }
      ?: errors.first()
  }
}
