package org.maplibre.compose.util

import co.touchlab.kermit.Logger
import org.maplibre.android.log.LoggerDefinition

internal class KermitLoggerDefinition(private val kermit: Logger?) : LoggerDefinition {
  override fun v(tag: String, msg: String) {
    kermit?.v(tag = tag) { msg }
  }

  override fun v(tag: String, msg: String, tr: Throwable) {
    kermit?.v(tag = tag, throwable = tr) { msg }
  }

  override fun d(tag: String, msg: String) {
    kermit?.d(tag = tag) { msg }
  }

  override fun d(tag: String, msg: String, tr: Throwable?) {
    kermit?.d(tag = tag, throwable = tr) { msg }
  }

  override fun i(tag: String, msg: String) {
    kermit?.i(tag = tag) { msg }
  }

  override fun i(tag: String, msg: String, tr: Throwable?) {
    kermit?.i(tag = tag, throwable = tr) { msg }
  }

  override fun w(tag: String, msg: String) {
    kermit?.w(tag = tag) { msg }
  }

  override fun w(tag: String, msg: String, tr: Throwable?) {
    kermit?.w(tag = tag, throwable = tr) { msg }
  }

  override fun e(tag: String, msg: String) {
    kermit?.e(tag = tag) { msg }
  }

  override fun e(tag: String, msg: String, tr: Throwable?) {
    kermit?.e(tag = tag, throwable = tr) { msg }
  }
}
