package org.maplibre.compose.logging

/** One diagnostic message from the library or a map engine. */
public class MapLogRecord(
  public val level: MapLogLevel,
  public val source: MapLogSource,
  /**
   * The engine's category when it reports one: a MapLibre Native event name such as `HttpRequest`,
   * or the MapLibre GL JS source or layer id that an error concerns.
   */
  public val category: String?,
  public val message: String,
  public val throwable: Throwable?,
) {
  override fun toString(): String =
    "MapLogRecord(level=$level, source=$source, category=$category, message=$message, throwable=$throwable)"
}
