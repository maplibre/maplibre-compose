package org.maplibre.kmp.native.util

import java.io.FileOutputStream
import java.nio.file.Files

internal object SharedLibraryLoader {
  private var loaded = false

  fun load() {
    if (loaded) return
    val libraryName = getLibraryName()
    extractAndLoadLibrary(libraryName)
  }

  private fun getLibraryName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
      os.contains("win") -> "maplibre-jni.dll"
      os.contains("mac") -> "libmaplibre-jni.dylib"
      else -> "libmaplibre-jni.so"
    }
  }

  @Suppress("UnsafeDynamicallyLoadedCode")
  private fun extractAndLoadLibrary(libraryName: String) {
    val resourcePath = "/$libraryName"
    val inputStream =
      SharedLibraryLoader::class.java.getResourceAsStream(resourcePath)
        ?: throw UnsatisfiedLinkError("Native library not found in JAR: $resourcePath")

    // Create a temporary file for the native library
    val tempDir = Files.createTempDirectory("maplibre-native-")
    val tempFile = tempDir.resolve(libraryName).toFile()
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
