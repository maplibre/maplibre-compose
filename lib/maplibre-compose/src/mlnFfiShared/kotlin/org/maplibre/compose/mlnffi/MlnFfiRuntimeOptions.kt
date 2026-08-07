package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Path

/** Platform-resolved configuration for a MapLibre Native FFI runtime. */
@Immutable
internal data class MlnFfiRuntimeOptions(
  val cachePath: Path,
  val maximumCacheSizeBytes: Long? = null,
)

/** The [MlnFfiRuntimeOptions] maps in this composition use. */
internal val LocalMlnFfiRuntimeOptions: ProvidableCompositionLocal<MlnFfiRuntimeOptions> =
  staticCompositionLocalOf {
    error("No MapLibre runtime options are installed. Use the platform's map host provider.")
  }
