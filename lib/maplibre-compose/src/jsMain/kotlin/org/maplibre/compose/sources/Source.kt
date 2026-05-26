package org.maplibre.compose.sources

import org.maplibre.kmp.js.stylespec.sources.SourceSpecification
import org.maplibre.kmp.js.source.Source as JsSource

public actual sealed class Source {
  internal abstract val spec: SourceSpecification

  internal abstract val impl: JsSource

  internal abstract fun bind(source: JsSource)

  internal actual val id: String by lazy { spec.id }

  public actual val attributionHtml: String get() = impl.attribution.orEmpty()

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
