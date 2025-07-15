package org.maplibre.compose.webview

import kotlinx.browser.window

private external interface KmpJsBridge {
  /** https://kevinnzou.github.io/compose-webview-multiplatform/communication/#webview-to-native */
  fun callNative(methodName: String, params: String, callback: ((String) -> Unit)?)
}

private val kmpJsBridge: KmpJsBridge?
  get() = window.asDynamic().kmpJsBridge.unsafeCast<KmpJsBridge?>()

internal fun <I> callNative(methodName: String, params: I) =
  kmpJsBridge?.callNative(methodName, JSON.stringify(params), null)
    ?: error("kmpJsBridge not yet initialized")

internal fun <I, O> callNative(methodName: String, params: I, callback: (O) -> Unit) =
  kmpJsBridge?.callNative(methodName, JSON.stringify(params)) { callback(JSON.parse(it)) }
    ?: error("kmpJsBridge not yet initialized")
