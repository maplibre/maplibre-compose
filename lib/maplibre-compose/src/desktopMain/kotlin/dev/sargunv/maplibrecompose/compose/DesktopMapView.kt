package dev.sargunv.maplibrecompose.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.jsbridge.rememberWebViewJsBridge
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.MaplibreMap
import dev.sargunv.maplibrecompose.core.SafeStyle
import dev.sargunv.maplibrecompose.core.WebviewBridge
import dev.sargunv.maplibrecompose.core.WebviewMap

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
  options: MapOptions,
) =
  DesktopMapView(
    modifier = modifier,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
  )

@Composable
internal fun DesktopMapView(
  modifier: Modifier,
  style: BaseStyle,
  update: suspend (map: MaplibreMap) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MaplibreMap.Callbacks,
) {
  val data = LocalMaplibreContext.current.webviewHtml
  val state = rememberWebViewStateWithHTMLData(data)
  val navigator = rememberWebViewNavigator()
  val jsBridge = rememberWebViewJsBridge(navigator)

  WebView(
    state = state,
    modifier = modifier.fillMaxWidth(),
    navigator = navigator,
    webViewJsBridge = jsBridge,
    onCreated = { _ -> },
    onDispose = { _ -> onReset() },
  )

  if (state.isLoading) return

  val map = remember(state) { WebviewMap(WebviewBridge(state.nativeWebView, "WebviewMapBridge")) }

  LaunchedEffect(map) { map.init() }

  LaunchedEffect(map, style) { map.asyncSetBaseStyle(style) }

  LaunchedEffect(map, update) { update(map) }
}

internal class MessageHandler(
  val name: String,
  val handler:
    (message: JsMessage, navigator: WebViewNavigator?, callback: (String) -> Unit) -> Unit,
) : IJsMessageHandler {
  override fun handle(
    message: JsMessage,
    navigator: WebViewNavigator?,
    callback: (String) -> Unit,
  ) = handler(message, navigator, callback)

  override fun methodName(): String = name
}
