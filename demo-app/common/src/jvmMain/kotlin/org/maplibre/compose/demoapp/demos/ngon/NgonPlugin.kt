package org.maplibre.compose.demoapp.demos.ngon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.outputStream
import org.maplibre.nativeffi.Maplibre

/** Whether MapLibre Native in this process knows the `ngon` layer type. */
sealed interface NgonPluginState {
  /** [NgonPlugin.ensureRegistered] has not run yet. */
  data object NotLoaded : NgonPluginState

  /** The plugin registered its layer type; [NgonLayer] draws. */
  data class Registered(val libraryName: String, val maplibreNativeCommit: String?) :
    NgonPluginState

  /** This platform or build cannot load the plugin. [reason] tells the user what is missing. */
  data class Unavailable(val reason: String) : NgonPluginState

  /** The library loaded but registration was refused. */
  data class Failed(val message: String) : NgonPluginState
}

/**
 * The n-gon layer plugin from MapLibre Native's `plugins/ngon-layer` sample, as a process-wide
 * singleton: MapLibre Native keeps a registered plugin for the rest of the process, so registration
 * happens once and every later map shares it.
 *
 * `mise run deps:ngon-plugin` builds the plugin from the MapLibre Native commit behind the pinned
 * maplibre-native-ffi release, and the desktop demo packages it as a resource. See
 * `.mise/bin/build-ngon-plugin`.
 */
object NgonPlugin {
  var state: NgonPluginState by mutableStateOf(NgonPluginState.NotLoaded)
    private set

  /** Registers the plugin on first use and returns the outcome, which never changes afterwards. */
  fun ensureRegistered(): NgonPluginState {
    if (state == NgonPluginState.NotLoaded) state = registerNgonPlugin()
    return state
  }
}

private const val LIBRARY_NAME = "mln-ngon-layer"
private const val RESOURCE_ROOT = "plugins/ngon-layer"
private const val ERROR_CAPACITY = 1024L

/**
 * Loads the plugin from the classpath through the Java FFM API and calls its C entry point,
 * `mln_ngon_layer_register(register_function, error_buffer, capacity)`, with the registration
 * function that maplibre-native-ffi exports. Both live in this JVM, so the plugin registers into
 * the same MapLibre Native the maps render with.
 */
private fun registerNgonPlugin(): NgonPluginState {
  val classifier =
    nativeClassifier()
      ?: return NgonPluginState.Unavailable(
        "No plugin build for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}."
      )
  val libraryFile = System.mapLibraryName(LIBRARY_NAME)
  val resourceDirectory = "$RESOURCE_ROOT/$classifier"
  val classLoader = NgonPlugin::class.java.classLoader
  val library =
    classLoader.getResourceAsStream("$resourceDirectory/$libraryFile")
      ?: return NgonPluginState.Unavailable(
        "The plugin library is not packaged with this build. Run `mise run deps:ngon-plugin`, " +
          "then relaunch the demo."
      )
  val commit =
    classLoader.getResourceAsStream("$resourceDirectory/ngon-layer.properties")?.use { stream ->
      Properties().apply { load(stream) }.getProperty("maplibre-native.commit")
    }

  return try {
    val path = library.use { extract(it, libraryFile) }
    // The library stays loaded for the process: MapLibre Native calls back into it from its tile
    // workers and render thread for as long as an `ngon` layer exists.
    val symbols = SymbolLookup.libraryLookup(path, Arena.global())
    val entryPoint =
      symbols.find("mln_ngon_layer_register").orElseThrow {
        IllegalStateException("$libraryFile does not export mln_ngon_layer_register")
      }
    val register =
      Linker.nativeLinker()
        .downcallHandle(
          entryPoint,
          FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
          ),
        )
    Arena.ofConfined().use { arena ->
      val error = arena.allocate(ERROR_CAPACITY)
      val hostRegister = MemorySegment.ofAddress(Maplibre.pluginRegisterFunctionV1().address)
      val status = register.invokeExact(hostRegister, error, ERROR_CAPACITY) as Int
      when (status) {
        // OK, or already registered by an earlier map in this process.
        0,
        1 -> NgonPluginState.Registered(libraryFile, commit)
        else -> {
          val message = error.getString(0).ifBlank { "status $status" }
          NgonPluginState.Failed("MapLibre Native refused the plugin: $message")
        }
      }
    }
  } catch (error: Throwable) {
    NgonPluginState.Failed(error.toString())
  }
}

private fun extract(library: java.io.InputStream, fileName: String): Path {
  val directory = Files.createTempDirectory("maplibre-compose-demo-ngon")
  val path = directory.resolve(fileName)
  path.outputStream().use { library.copyTo(it) }
  path.toFile().deleteOnExit()
  directory.toFile().deleteOnExit()
  return path
}

/** The resource directory naming that the maplibre-native-ffi runtime JARs use. */
private fun nativeClassifier(): String? {
  val osName = System.getProperty("os.name").lowercase()
  val architecture = System.getProperty("os.arch").lowercase()
  val arm64 = architecture == "aarch64" || architecture == "arm64"
  return when {
    osName.contains("windows") && arm64 -> "natives-windows-arm64"
    osName.contains("windows") -> "natives-windows-x64"
    osName.contains("mac") && arm64 -> "natives-macos-arm64"
    osName.contains("linux") && arm64 -> "natives-linux-arm64"
    osName.contains("linux") -> "natives-linux-x64"
    else -> null
  }
}
