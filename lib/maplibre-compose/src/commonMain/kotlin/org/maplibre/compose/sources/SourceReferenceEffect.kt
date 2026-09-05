package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.maplibre.compose.style.LocalStyleNode

@Composable
internal fun SourceReferenceEffect(source: Source) {
  val node = LocalStyleNode.current
  DisposableEffect(node, source) {
    when (node.sourceManager.getBaseSource(source.id)) {
      null -> {
        node.sourceManager.addReference(source)
        onDispose { node.sourceManager.removeReference(source) }
      }
      source -> onDispose {}
      else -> error("Source id '${source.id}' conflicts with a base source")
    }
  }
}
