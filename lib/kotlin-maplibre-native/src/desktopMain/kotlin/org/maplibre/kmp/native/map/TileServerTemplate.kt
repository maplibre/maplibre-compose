package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class TileServerTemplate(
  @get:CalledByNative val template: String,
  @get:CalledByNative val domainName: String = "",
  @get:CalledByNative val versionPrefix: String? = null,
)
