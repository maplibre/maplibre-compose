package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.maplibre.compose.style.LocalStyleNode

@Composable
internal fun SourceReferenceEffect(source: Source) {
  val node = LocalStyleNode.current
  DisposableEffect(source) {
    val referenced = node.sourceManager.addReference(source)
    onDispose { if (referenced) node.sourceManager.removeReference(source) }
  }
}
