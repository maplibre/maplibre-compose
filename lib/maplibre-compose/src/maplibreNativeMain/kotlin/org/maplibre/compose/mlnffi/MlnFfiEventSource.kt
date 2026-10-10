package org.maplibre.compose.mlnffi

import org.maplibre.nativeffi.generated.MapHandle

/**
 * The value that `RuntimeEvent.source` carries for the events [this] map raises.
 *
 * STOPGAP: the Kotlin binding exposes no public way to tell which map raised an event, which a
 * runtime with more than one map needs to route them. Each actual reads the binding's internal
 * handle value. Delete this file once the binding exposes the event source of a map handle.
 */
internal expect fun MapHandle.eventSource(): ULong
