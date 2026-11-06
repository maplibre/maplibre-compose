package org.maplibre.compose.benchmark

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Tee this process's standard output to `benchmark.log` in the app's external files directory.
 *
 * The Android runner reads that file rather than logcat, which drops lines when a run reports its
 * results in a burst. The console copy stays, so Logcat still shows failures.
 */
fun recordBenchmarkOutput(context: Context) {
  val console = System.out
  val file = FileOutputStream(File(context.getExternalFilesDir(null), "benchmark.log"))
  val tee =
    object : OutputStream() {
      override fun write(value: Int) {
        file.write(value)
        console.write(value)
      }

      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        file.write(bytes, offset, length)
        console.write(bytes, offset, length)
      }

      override fun flush() {
        file.flush()
        console.flush()
      }
    }
  System.setOut(PrintStream(tee, true))
}
