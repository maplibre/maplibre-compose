package org.maplibre.compose.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.datlag.kcef.KCEF
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
public fun KcefProvider(loading: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
  var initialized by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      KCEF.init({
        // TODO https://github.com/harawata/appdirs
        installDir(File("kcef-bundle"))
        appHandler(CustomCefAppHandler)
      })
    }
    initialized = true
  }

  if (initialized) content() else loading()

  DisposableEffect(Unit) { onDispose { KCEF.disposeBlocking() } }
}
