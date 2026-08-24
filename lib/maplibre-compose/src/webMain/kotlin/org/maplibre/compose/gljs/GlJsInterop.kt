package org.maplibre.compose.gljs

import js.typedarrays.Uint8Array
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.toJsString
import web.gl.GLenum
import web.gl.WebGL2RenderingContext

internal const val GL_MAX_TEXTURE_IMAGE_UNITS: Int = 0x8872
internal const val GL_MAX_TEXTURE_SIZE: Int = 0x0D33
internal const val GL_SCISSOR_TEST: Int = 0x0C11

/** `value == null` in JavaScript, so it is true for both `null` and `undefined`. */
internal fun isJsNullish(value: JsAny?): Boolean = js("value == null")

internal fun parseJson(text: String): JsAny? = js("JSON.parse(text)")

internal fun stringifyJson(value: JsAny?): String? = js("JSON.stringify(value)")

internal fun objectKeys(obj: JsAny): JsArray<JsString> = js("Object.keys(obj)")

internal fun jsStringToKotlin(value: JsString): String = js("value")

internal fun jsNumberToDouble(value: JsNumber): Double = js("value")

internal fun jsNumberAt(arr: JsArray<JsNumber>, index: Int): Double = js("arr[index]")

internal fun setUint8At(target: Uint8Array<*>, index: Int, value: Int): Unit =
  js("{ target[index] = value }")

internal fun jsPair(a: Double, b: Double): JsArray<JsNumber> = js("[a, b]")

internal fun jsQuad(a: Double, b: Double, c: Double, d: Double): JsArray<JsNumber> =
  js("[a, b, c, d]")

internal fun jsPairAny(a: JsAny, b: JsAny): JsArray<JsAny> = js("[a, b]")

internal fun JsString.toKotlinString(): String = jsStringToKotlin(this)

internal fun JsArray<JsString>.toKotlinStrings(): List<String> =
  toList().map { it.toKotlinString() }

internal fun List<String>.toJsStringArray(): JsArray<JsString> {
  val arr = JsArray<JsString>()
  forEachIndexed { index, value -> arr[index] = value.toJsString() }
  return arr
}

internal fun glGetNumber(gl: WebGL2RenderingContext, pname: Int): Double? =
  js("typeof gl.getParameter(pname) === 'number' ? gl.getParameter(pname) : null")

internal fun glEnum(value: Int): GLenum = js("value")

internal fun bindSamplerNone(gl: WebGL2RenderingContext, unit: Int): Unit =
  js("{ gl.bindSampler(unit, null) }")

internal fun isJsFunction(value: JsAny?): Boolean = js("typeof value === 'function'")

/**
 * Identity at the JS boundary; needed because Wasm `unsafeCast` does not accept a null receiver.
 */
internal fun <T : JsAny?> jsUnsafeCast(value: JsAny?): T = js("value")

internal fun jsGet(obj: JsAny, name: String): JsAny? = js("obj[name]")

internal fun call0Boolean(receiver: JsAny, name: String): Boolean = js("receiver[name]()")
