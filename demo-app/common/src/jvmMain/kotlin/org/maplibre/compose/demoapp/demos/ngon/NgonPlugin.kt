package org.maplibre.compose.demoapp.demos.ngon

import co.touchlab.kermit.Logger
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.outputStream
import org.maplibre.nativeffi.Maplibre

/**
 * MapLibre Native's sample n-gon layer plugin, built by `mise run deps:ngon-plugin` and packaged as
 * a resource. Registration is process-wide, so it happens once.
 */
internal object NgonPlugin {
  /** Whether the `ngon` layer type is registered. The first read loads the plugin. */
  val isRegistered: Boolean by lazy { register() }

  private val log = Logger.withTag("NgonPlugin")

  /** Loads the library through the Java FFM API and calls `mln_ngon_layer_register`. */
  private fun register(): Boolean {
    val libraryFile = System.mapLibraryName(LIBRARY_NAME)
    val library =
      NgonPlugin::class.java.classLoader.getResourceAsStream("$RESOURCE_ROOT/$libraryFile")
    if (library == null) {
      log.i { "$libraryFile is not on the classpath; run `mise run deps:ngon-plugin` to build it." }
      return false
    }
    return try {
      val path = library.use { extract(it, libraryFile) }
      // MapLibre Native calls back into the library for the rest of the process.
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
        // OK, or already registered by an earlier map in this process.
        val registered = status == 0 || status == 1
        if (!registered) {
          log.w {
            "MapLibre Native refused the plugin: ${error.getString(0).ifBlank { "status $status" }}"
          }
        }
        registered
      }
    } catch (error: Throwable) {
      log.w(error) { "Loading $libraryFile failed" }
      false
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

  private const val LIBRARY_NAME = "mln-ngon-layer"
  private const val RESOURCE_ROOT = "plugins/ngon-layer"
  private const val ERROR_CAPACITY = 1024L
}
