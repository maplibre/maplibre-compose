package org.maplibre.compose.overlay

import androidx.compose.runtime.Composable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import org.maplibre.compose.desktop.bridge.ObjectiveC

@Composable
internal actual fun systemDefaultPrimaryMeasure(): ScaleBarMeasure? {
  val osName = System.getProperty("os.name").orEmpty()
  return when {
    osName.startsWith("Mac", ignoreCase = true) -> macosSystemDefaultPrimaryMeasure()
    osName.startsWith("Linux", ignoreCase = true) -> linuxEnvironmentMeasure
    osName.startsWith("Windows", ignoreCase = true) -> windowsSystemDefaultPrimaryMeasure()
    else -> null
  }
}

/** The environment that glibc reads is fixed for the life of the process. */
private val linuxEnvironmentMeasure by lazy { linuxSystemDefaultPrimaryMeasure() }

internal fun macosSystemDefaultPrimaryMeasure(): ScaleBarMeasure? = runCatching {
  ObjectiveC.runInAutoreleasePool {
    val locale = ObjectiveC.sendClassPointer("NSLocale", "currentLocale")
    val key = ObjectiveC.nsString("kCFLocaleMeasurementSystemKey")
    val value = ObjectiveC.sendPointer(locale, "objectForKey:", key)
    scaleBarMeasureForAppleMeasurementSystem(ObjectiveC.utf8String(value))
  }
}
  .getOrNull()

/**
 * Reads glibc's `LC_MEASUREMENT` category for [localeName]. The empty name selects the locale glibc
 * resolves from the environment.
 */
internal fun linuxSystemDefaultPrimaryMeasure(localeName: String = ""): ScaleBarMeasure? =
  runCatching {
    val linker = Linker.nativeLinker()
    fun function(name: String, descriptor: FunctionDescriptor) =
      linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor)

    val newlocale =
      function(
        "newlocale",
        FunctionDescriptor.of(
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        ),
      )
    val nlLanginfoL =
      function(
        "nl_langinfo_l",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
      )
    val freelocale = function("freelocale", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    val locale =
      Arena.ofConfined().use { arena ->
        newlocale.invokeWithArguments(
          LcMeasurementMask,
          arena.allocateFrom(localeName),
          MemorySegment.NULL,
        ) as MemorySegment
      }
    if (locale.address() == 0L) return@runCatching null
    try {
      val measurement =
        (nlLanginfoL.invokeWithArguments(NlMeasurementMeasurement, locale) as MemorySegment)
          .reinterpret(1)
          .get(ValueLayout.JAVA_BYTE, 0)
      when (measurement.toInt()) {
        1 -> ScaleBarMeasure.Metric
        2 -> ScaleBarMeasure.FeetAndMiles
        else -> null
      }
    } finally {
      freelocale.invokeWithArguments(locale)
    }
  }
  .getOrNull()

/** Reads the measurement system of the user's Windows regional format. */
internal fun windowsSystemDefaultPrimaryMeasure(): ScaleBarMeasure? = runCatching {
  Arena.ofConfined().use { arena ->
    val measurement = arena.allocate(ValueLayout.JAVA_INT)
    val written =
      getLocaleInfoEx.invokeWithArguments(
        MemorySegment.NULL,
        LocaleIMeasure or LocaleReturnNumber,
        measurement,
        Int.SIZE_BYTES / Char.SIZE_BYTES,
      ) as Int
    if (written == 0) return@runCatching null
    when (measurement.get(ValueLayout.JAVA_INT, 0)) {
      0 -> ScaleBarMeasure.Metric
      1 -> ScaleBarMeasure.FeetAndMiles
      else -> null
    }
  }
}
  .getOrNull()

private val getLocaleInfoEx by lazy {
  Linker.nativeLinker()
    .downcallHandle(
      SymbolLookup.libraryLookup("kernel32", Arena.global()).findOrThrow("GetLocaleInfoEx"),
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
      ),
    )
}

private const val LcMeasurement = 11
private const val LcMeasurementMask = 1 shl LcMeasurement
private const val NlMeasurementMeasurement = LcMeasurement shl 16
private const val LocaleIMeasure = 0x0D
private const val LocaleReturnNumber = 0x20000000
