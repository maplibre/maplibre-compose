package org.maplibre.compose.sources

/** Represents a layer that exists in the base style but was not added by user Compose code. */
public actual class UnknownSource(
  private val _id: String,
  internal val jsonSpec: String,
) : Source() {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val _sourceId: String get() = _id
  override fun toJson(): String = jsonSpec
}
