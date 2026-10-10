package org.maplibre.compose.mlnffi

import java.lang.reflect.Field
import org.maplibre.nativeffi.generated.MapHandle

private val bindingField: Field =
  MapHandle::class.java.getDeclaredField("binding").apply { isAccessible = true }

private val handleField: Field =
  bindingField.type.getDeclaredField("handleId").apply { isAccessible = true }

internal actual fun MapHandle.eventSource(): ULong =
  (handleField.get(bindingField.get(this)) as Long).toULong()
