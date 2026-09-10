package org.maplibre.compose.resource

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread

/**
 * The work reads resources through `NSURL`/`NSData`, which autorelease onto a pool that a raw
 * pthread never drains. A thread-scoped pool that drains at thread exit reclaims them, and a
 * one-shot thread needs no finer granularity.
 */
@OptIn(BetaInteropApi::class)
internal actual fun startMlnFfiBlockingWork(name: String, work: () -> Unit) {
  MlnFfiOwnerThread(name) { autoreleasepool { work() } }.start()
}
