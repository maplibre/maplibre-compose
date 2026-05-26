package org.maplibre.compose.util

internal fun <T : Any> jso(): T = js("({})") as T

internal inline fun <T : Any> jso(block: T.() -> Unit): T = jso<T>().apply(block)
