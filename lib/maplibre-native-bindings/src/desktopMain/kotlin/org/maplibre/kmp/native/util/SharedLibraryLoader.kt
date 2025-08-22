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
    throw UnsatisfiedLinkError("Failed to load native library: $errors")
  }

  private fun getLibraryPaths(): List<String> {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osPart =
      when {
        os.contains("windows") -> "windows"
        os.contains("mac") -> "macos"
        os.contains("linux") -> "linux"
        else -> throw UnsatisfiedLinkError("Unsupported operating system: $os")
      }

    val rendererParts =
      when {
        os.contains("windows") -> listOf("opengl", "vulkan")
        os.contains("mac") -> listOf("metal", "vulkan")
        os.contains("linux") -> listOf("opengl", "vulkan")
        else -> throw UnsatisfiedLinkError("Unsupported renderer for OS: $os")
      }

    val filePart =
      when {
        os.contains("windows") -> "maplibre-jni.dll"
        os.contains("mac") -> "libmaplibre-jni.dylib"
        os.contains("linux") -> "libmaplibre-jni.so"
        else -> throw UnsatisfiedLinkError("Unsupported operating system: $os")
      }

    return rendererParts.map { "$osPart/$arch/$it/$filePart" }
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
