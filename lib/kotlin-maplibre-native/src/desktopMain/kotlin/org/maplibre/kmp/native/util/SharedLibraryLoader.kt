package org.maplibre.kmp.native.util

import java.io.FileOutputStream
import java.nio.file.Files

internal object SharedLibraryLoader {
  private var loaded = false

  fun load() {
    if (loaded) return
    extractAndLoadLibrary(getLibraryPath())
  }

  private fun getLibraryPath(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osPart =
      when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        os.contains("nix") -> "linux"
        else -> throw UnsatisfiedLinkError("Unsupported operating system: $os")
      }

    // TODO overridable somehow
    val rendererPart =
      when {
        os.contains("windows") -> "opengl"
        os.contains("mac") -> "metal"
        os.contains("linux") -> "vulkan"
        else -> throw UnsatisfiedLinkError("Unsupported renderer for OS: $os")
      }

    val filePart =
      when {
        os.contains("windows") -> "maplibre-jni.dll"
        os.contains("mac") -> "libmaplibre-jni.dylib"
        os.contains("linux") -> "libmaplibre-jni.so"
        else -> throw UnsatisfiedLinkError("Unsupported operating system: $os")
      }

    return "$osPart/$arch/$rendererPart/$filePart"
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
