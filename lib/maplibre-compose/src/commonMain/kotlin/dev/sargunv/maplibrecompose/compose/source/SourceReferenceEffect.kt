package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.sargunv.maplibrecompose.compose.engine.LocalStyleNode
import dev.sargunv.maplibrecompose.core.source.Source

@Composable
internal fun SourceReferenceEffect(source: Source) {
  val node = LocalStyleNode.current
  DisposableEffect(source) {
    node.sourceManager.addReference(source)
    onDispose { node.sourceManager.removeReference(source) }
  }
}
