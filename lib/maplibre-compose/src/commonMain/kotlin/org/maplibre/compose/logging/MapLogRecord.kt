package org.maplibre.compose.logging

/**
 * One diagnostic message from the library or a map engine.
 *
 * @property level The severity of the message.
 * @property source The component that reported the message.
 * @property category The engine's category when it reports one: a MapLibre Native event name such
 *   as `HttpRequest`, or the MapLibre GL JS source or layer id that an error concerns.
 * @property message The text of the message.
 * @property throwable The exception that the message concerns, when there is one.
 */
public class MapLogRecord
internal constructor(
  public val level: MapLogLevel,
  public val source: MapLogSource,
  public val category: String? = null,
  public val message: String,
  public val throwable: Throwable? = null,
) {
  override fun toString(): String =
    "MapLogRecord(level=$level, source=$source, category=$category, message=$message, throwable=$throwable)"
}
