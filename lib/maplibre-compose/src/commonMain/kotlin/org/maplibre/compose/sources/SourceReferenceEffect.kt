package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.maplibre.compose.style.LocalStyleNode

@Composable
internal fun SourceReferenceEffect(source: Source) {
  val node = LocalStyleNode.current
  DisposableEffect(source) {
    when (node.sourceManager.getBaseSource(source.id)) {
      null -> {
        // free to reference a new source
        node.sourceManager.addReference(source)
        onDispose { node.sourceManager.removeReference(source) }
      }
      source -> {
        // a base source was referenced; no-op
        onDispose {}
      }
      else -> {
        // not the base source, but conflicting id with a base source
        error("Source id '${source.id}' conflicts with a base source")
      }
    }
  }
}
