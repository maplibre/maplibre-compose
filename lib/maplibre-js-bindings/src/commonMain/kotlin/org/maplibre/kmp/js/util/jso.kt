package org.maplibre.kmp.js.util

internal fun <T : Any> jso(): T = js("({})") as T

internal inline fun <T : Any> jso(block: T.() -> Unit): T = jso<T>().apply(block)
