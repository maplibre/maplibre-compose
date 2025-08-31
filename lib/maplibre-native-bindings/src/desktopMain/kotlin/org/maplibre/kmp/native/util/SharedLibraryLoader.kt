package org.maplibre.kmp.native.util

import java.io.FileOutputStream
import java.nio.file.Files

internal object SharedLibraryLoader {
  private var loaded = false

  fun load() {
    if (loaded) return
    val errors = mutableListOf<UnsatisfiedLinkError>()
    getLibraryPaths().forEach { path ->
      try {
        extractAndLoadLibrary(path)
        return
      } catch (e: UnsatisfiedLinkError) {
        errors.add(e)
      }
    }
    throw errors.firstOrNull { !(it.message?.contains("not found in JAR") ?: false) }
      ?: errors.first()
  }

  private fun getLibraryPaths(): List<String> {
    val os =
      when (val os = System.getProperty("os.name").lowercase()) {
        "mac os x" -> "macos"
        else -> os.split(" ").first()
      }

    val arch = System.getProperty("os.arch").lowercase()

    val renderers =
      when (os) {
        "macos" -> listOf("metal", "vulkan")
        else -> listOf("opengl", "vulkan")
      }

    val file =
      when (os) {
        "windows" -> "maplibre-jni.dll"
        "macos" -> "libmaplibre-jni.dylib"
        else -> "libmaplibre-jni.so"
      }

    return renderers.map { renderer -> "$os/$arch/$renderer/$file" }
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
}
