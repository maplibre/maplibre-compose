package org.maplibre.compose.benchmark

import android.app.ActivityManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import kotlin.math.min
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val BENCH_TAG = "MapLibreBench"

internal class FrameClock {
  private val lock = Any()
  private val stampsNs = ArrayList<Long>(4096)

  fun record() {
    val now = SystemClock.elapsedRealtimeNanos()
    synchronized(lock) { stampsNs.add(now) }
  }

  fun reset() {
    synchronized(lock) { stampsNs.clear() }
  }

  fun count(): Int = synchronized(lock) { stampsNs.size }

  fun snapshotNs(): LongArray = synchronized(lock) { stampsNs.toLongArray() }
}

internal data class MemorySample(
  val elapsedMs: Long,
  val totalPssKb: Int,
  val nativePssKb: Int,
  val dalvikPssKb: Int,
  val graphicsPssKb: Int,
  val javaUsedKb: Long,
  val nativeHeapKb: Long,
)

internal class MemorySampler(private val activityManager: ActivityManager) {
  fun sample(elapsedMs: Long): MemorySample {
    val info = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).first()
    val runtime = Runtime.getRuntime()
    return MemorySample(
      elapsedMs = elapsedMs,
      totalPssKb = info.totalPss,
      nativePssKb = info.nativePss,
      dalvikPssKb = info.dalvikPss,
      graphicsPssKb = info.statKb("summary.graphics"),
      javaUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
      nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
    )
  }
}

private fun Debug.MemoryInfo.statKb(key: String): Int = getMemoryStat(key)?.toIntOrNull() ?: -1

internal fun percentile(sorted: List<Double>, p: Double): Double {
  if (sorted.isEmpty()) return Double.NaN
  val index = (p / 100.0) * (sorted.size - 1)
  val low = index.toInt()
  val high = min(low + 1, sorted.size - 1)
  val fraction = index - low
  return sorted[low] * (1.0 - fraction) + sorted[high] * fraction
}

internal fun frameIntervalsMs(stampsNs: LongArray): List<Double> {
  if (stampsNs.size < 2) return emptyList()
  return (1 until stampsNs.size).map { i -> (stampsNs[i] - stampsNs[i - 1]) / 1_000_000.0 }
}

internal fun largePointCollectionJson(
  count: Int,
  originLon: Double = -10.0,
  originLat: Double = 40.0,
  step: Double = 0.05,
): String {
  val builder = StringBuilder(count * 90)
  builder.append("""{"type":"FeatureCollection","features":[""")
  for (index in 0 until count) {
    if (index > 0) builder.append(',')
    val longitude = originLon + (index % 200) * step
    val latitude = originLat + (index / 200) * step
    builder.append(
      """{"type":"Feature","geometry":{"type":"Point","coordinates":[$longitude,$latitude]},"properties":{}}"""
    )
  }
  builder.append("]}")
  return builder.toString()
}

internal fun JsonObjectBuilder.putStats(name: String, values: List<Double>) {
  val sorted = values.sorted()
  putJsonObject(name) {
    put("n", sorted.size)
    if (sorted.isEmpty()) return@putJsonObject
    put("mean", sorted.average())
    put("min", sorted.first())
    put("p50", percentile(sorted, 50.0))
    put("p95", percentile(sorted, 95.0))
    put("p99", percentile(sorted, 99.0))
    put("max", sorted.last())
  }
}

internal fun JsonObjectBuilder.putMemory(name: String, samples: List<MemorySample>) {
  putJsonObject(name) {
    if (samples.isEmpty()) return@putJsonObject
    put("samples", samples.size)
    put("startTotalPssKb", samples.first().totalPssKb)
    put("endTotalPssKb", samples.last().totalPssKb)
    put("peakTotalPssKb", samples.maxOf { it.totalPssKb })
    put("meanTotalPssKb", samples.map { it.totalPssKb.toDouble() }.average())
    put("peakNativePssKb", samples.maxOf { it.nativePssKb })
    put("peakDalvikPssKb", samples.maxOf { it.dalvikPssKb })
    put("peakGraphicsPssKb", samples.maxOf { it.graphicsPssKb })
    put("peakJavaUsedKb", samples.maxOf { it.javaUsedKb })
    put("peakNativeHeapKb", samples.maxOf { it.nativeHeapKb })
  }
}

internal fun writeBenchmarkReport(file: File, report: JsonObject) {
  file.parentFile?.mkdirs()
  file.writeText(report.toString())
  Log.i(BENCH_TAG, report.toString())
  Log.i(BENCH_TAG, "Wrote ${file.absolutePath}")
}

internal fun shell(command: String): String {
  val stream =
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
      .uiAutomation
      .executeShellCommand(command)
  return FileInputStream(stream.fileDescriptor).bufferedReader().use { it.readText() }
}

internal fun gfxinfoReset(packageName: String) {
  runCatching { shell("dumpsys gfxinfo $packageName reset") }
}

internal fun gfxinfoSummary(packageName: String): JsonObject {
  val dump = runCatching {
    shell("dumpsys gfxinfo $packageName")
  }
    .getOrElse {
      return buildJsonObject {}
    }
  return buildJsonObject {
    Regex("""Total frames rendered:\s+(\d+)""").find(dump)?.let {
      put("totalFramesRendered", it.groupValues[1].toInt())
    }
    Regex("""Janky frames:\s+(\d+)\s+\(([\d.]+)%\)""").find(dump)?.let { match ->
      put("jankyFrames", match.groupValues[1].toInt())
      put("jankyPercent", match.groupValues[2].toDouble())
    }
    Regex("""50th percentile:\s+(\d+)ms""").find(dump)?.let {
      put("percentile50Ms", it.groupValues[1].toInt())
    }
    Regex("""90th percentile:\s+(\d+)ms""").find(dump)?.let {
      put("percentile90Ms", it.groupValues[1].toInt())
    }
    Regex("""95th percentile:\s+(\d+)ms""").find(dump)?.let {
      put("percentile95Ms", it.groupValues[1].toInt())
    }
    Regex("""99th percentile:\s+(\d+)ms""").find(dump)?.let {
      put("percentile99Ms", it.groupValues[1].toInt())
    }
  }
}

internal fun meminfoSummary(packageName: String): String = runCatching {
  shell("dumpsys meminfo $packageName")
}
  .getOrDefault("")
  .lineSequence()
  .take(50)
  .joinToString("\n")
