# Logging

How MapLibre Compose delivers diagnostics, and how an application routes them
into its own logging library.

## The contract

The library owns a small logging contract in `org.maplibre.compose.logging` and
depends on no logging library. An application adapts the contract to the library
that it uses, in a few lines.

```kotlin
public enum class MapLogLevel { Debug, Info, Warning, Error }

public enum class MapLogSource { Library, NativeEngine, WebEngine }

public class MapLogRecord(
  public val level: MapLogLevel,
  public val source: MapLogSource,
  /** The engine's category when it reports one: a native event name, or a GL JS source or layer id. */
  public val category: String?,
  public val message: String,
  public val throwable: Throwable?,
)

public fun interface MapLogger {
  /** Records below this level are dropped before their message is built. */
  public val minLevel: MapLogLevel get() = MapLogLevel.Debug
  public fun log(record: MapLogRecord)
}

public object MapLogging {
  /** The sink for every record. Null drops every record. Defaults to [platformLogger]. */
  public var logger: MapLogger?
  /** Writes to the platform log: logcat, NSLog, stderr, or the browser console. */
  public val platformLogger: MapLogger
}
```

`MapLogger.log` runs on any thread, including engine worker threads, and may run
while the native engine holds its logging lock. An implementation returns
quickly and calls no map API.

A single record parameter keeps the interface evolvable. A field added to
`MapLogRecord` breaks no implementer.

Four levels match the levels that both engines and the browser console
distinguish. MapLibre Native compiles debug records out of release builds and
never delivers them to a callback.

## Configuration is process-global

`MapLogging.logger` is the only configuration point. The runtime options carry
no logger.

- The native log callback is process-global, and its records name no runtime.
- The browser console is process-global.
- The default runtime from `rememberDefaultMapRuntime` takes no options, so a
  per-runtime setting misses the common case.
- Every logging library that an application would plug in is itself a process
  singleton.

Tests that assert on log output install a recording logger and restore the
previous one. Each platform test process runs without parallel forks.

## Engine bridges

The library installs each engine's log seam once and forwards to the current
`MapLogging.logger`. Changing the logger later reinstalls nothing.

### MapLibre Native

`Maplibre.setLogCallback` is the one seam. The library installs the callback
when the first native runtime is created, after the platform initialization that
loads the native library. The callback reads `MapLogging.logger` at call time.
It maps `LogSeverity` to `MapLogLevel`, sets the source to `NativeEngine`, and
puts the `LogEvent` name in `category`. The callback consumes every record. The
engine's own fall-through sink is stderr on every platform this build targets,
so leaving records to it loses them on Android
([maplibre-native-ffi#679](https://github.com/maplibre/maplibre-native-ffi/issues/679)).

The library changes no async severity mask. The engine's default delivers info
and warning records asynchronously and error records synchronously.

Structured runtime events such as a failed style load or a render error stay on
their existing paths: the lifecycle callbacks first, then the library log.

### MapLibre GL JS

The `error` event on the map is the one seam. The library's existing listener
forwards each event at `Error` level with the source `WebEngine` and the event's
source or layer id as `category`. Attaching that listener is also what silences
the browser's own `console.error` fallback, so `Error` is the honest level.

GL JS writes warnings, including style validation warnings, to `console.warn`
directly, and its workers write to their own consoles. Neither is redirectable.
The library leaves them in the browser console, which is the platform log on the
web in the same way that logcat is on Android.

## Inside the library

Library code logs through an internal `MapLog` helper with the same call shape
as the previous Kermit logger: a level method, an optional throwable, and a lazy
message. The helper checks `minLevel` before it builds the message. Sessions and
bindings take an optional `MapLog` so tests inject a recorder without touching
the global.

The library's own records use the source `Library` and no category. The previous
verbose level collapsed into `Debug`.

## Platform sinks

The default logger writes at the matching level to `android.util.Log` on
Android, `NSLog` on iOS, standard error on the JVM, and the matching `console`
method in the browser. The tag is `maplibre-compose`. This reproduces the output
that the Kermit default produced, so an application that configures nothing sees
the same log.
