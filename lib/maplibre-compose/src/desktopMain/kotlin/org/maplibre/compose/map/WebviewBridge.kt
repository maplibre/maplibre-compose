package org.maplibre.compose.map

import dev.datlag.kcef.KCEFBrowser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

internal class WebviewBridge(
  private val browser: KCEFBrowser,
  objectName: String,
  moduleName: String = "maplibre-compose-webview",
) {
  private val instance =
    "globalThis[${JsonPrimitive(moduleName)}][${JsonPrimitive(objectName)}].getInstance()"

  suspend inline fun <reified T> get(propertyName: String): T {
    println("Getting $propertyName")
    val fn = JsonPrimitive(propertyName)
    val result = browser.evaluateJavaScript("$instance[$fn]")
    return Json.decodeFromString(result!!)
  }

  suspend inline fun <reified T> set(propertyName: String, param: T) {
    println("Setting $propertyName")
    val fn = JsonPrimitive(propertyName)
    val arg = Json.encodeToJsonElement(param)
    browser.evaluateJavaScript("$instance[$fn] = ($arg)")
  }

  suspend inline fun <reified I> callVoid(methodName: String, param: I) {
    println("Calling $methodName")
    val fn = JsonPrimitive(methodName)
    val arg = Json.encodeToJsonElement(param)
    browser.evaluateJavaScript("$instance[$fn]($arg)")
  }

  suspend inline fun callVoid(methodName: String) {
    println("Calling $methodName")
    val fn = JsonPrimitive(methodName)
    browser.evaluateJavaScript("$instance[$fn]()")
  }

  suspend inline fun <reified I, reified O> call(methodName: String, param: I): O {
    println("Calling $methodName")
    val fn = JsonPrimitive(methodName)
    val arg = Json.encodeToJsonElement(param)
    val result = browser.evaluateJavaScript("$instance[$fn]($arg)")
    return Json.decodeFromString(result!!)
  }

  suspend inline fun <reified O> call(methodName: String): O {
    println("Calling $methodName")
    val fn = JsonPrimitive(methodName)
    val result = browser.evaluateJavaScript("$instance[$fn]()")
    return Json.decodeFromString(result!!)
  }
}
