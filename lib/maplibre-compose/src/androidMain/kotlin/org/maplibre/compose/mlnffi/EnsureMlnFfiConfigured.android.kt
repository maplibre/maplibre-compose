package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.maplibre.compose.android.AndroidRuntimeOptions
import org.maplibre.compose.android.androidCacheFile
import org.maplibre.compose.android.toMlnFfiRuntimeOptions

@Composable
internal actual fun CaptureMlnFfiPlatformContext() {
  AndroidMlnFfiPlatform.initialize(LocalContext.current)
}

internal actual fun ensureMlnFfiConfigured() {
  MlnFfiApplication.ensureConfigured {
    // The default cache lives in the app's cache directory, so it needs the application context.
    val context =
      AndroidMlnFfiPlatform.applicationOrNull
        ?: error(
          "MapLibre has no Android context yet; call MapLibre.configure(context) or compose a " +
            "map before using MaplibreRuntime"
        )
    AndroidRuntimeOptions(androidCacheFile(context)).toMlnFfiRuntimeOptions()
  }
}
