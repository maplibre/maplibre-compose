package org.maplibre.compose.sources

import org.maplibre.kmp.js.stylespec.sources.SourceSpecification

public actual class UnknownSource(override val impl: Nothing) : Source() {
  override val spec: SourceSpecification
    get() = TODO("Not yet implemented")

  override fun bind(source: org.maplibre.kmp.js.source.Source) {
    TODO("Not yet implemented")
  }
}
