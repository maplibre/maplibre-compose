package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.util.MaplibreComposable

@OptIn(ExperimentalTestApi::class)
internal class MapTestScope(
  val styleState: StyleState,
  val logWriter: CapturingLogWriter,
  private val baseStyleState: MutableState<BaseStyle>,
  private val composeUiTest: ComposeUiTest,
) {
  val style: Style?
    get() = styleState.styleNode?.style

  fun switchStyle(newStyle: BaseStyle) {
    composeUiTest.runOnUiThread { baseStyleState.value = newStyle }
  }

  fun waitForStyleLoad(timeoutMillis: Long = 10_000) {
    composeUiTest.waitUntil(timeoutMillis = timeoutMillis) { style != null }
  }

  fun waitForStyleReload(timeoutMillis: Long = 10_000) {
    composeUiTest.waitUntil(timeoutMillis = timeoutMillis / 2) { style == null }
    composeUiTest.waitUntil(timeoutMillis = timeoutMillis / 2) { style != null }
  }

  fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    composeUiTest.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
  }

  fun assertNoLogErrors() {
    val errors = logWriter.errors()
    if (errors.isNotEmpty()) {
      error(
        "MapLibre logged ${errors.size} error(s):\n" +
          errors.joinToString("\n") { "  [${it.severity}] ${it.message}" }
      )
    }
  }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.withMap(
  initialStyle: BaseStyle = BaseStyle.Empty,
  content: @Composable @MaplibreComposable () -> Unit = {},
  block: MapTestScope.() -> Unit,
) {
  val logWriter = CapturingLogWriter()
  val logger = Logger(loggerConfigInit(logWriter))
  val styleState = StyleState()
  val baseStyleState = mutableStateOf(initialStyle)

  setContent {
    val currentStyle by baseStyleState
    MaplibreMap(
      baseStyle = currentStyle,
      styleState = styleState,
      logger = logger,
      content = content,
    )
  }

  val scope = MapTestScope(styleState, logWriter, baseStyleState, this)
  scope.waitForStyleLoad()
  scope.block()
}
