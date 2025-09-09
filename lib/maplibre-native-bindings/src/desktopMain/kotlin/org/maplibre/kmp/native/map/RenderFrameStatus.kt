package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class RenderFrameStatus
@CalledByNative
public constructor(
  val mode: RenderMode,
  val needsRepaint: Boolean,
  val placementChanged: Boolean,
  val renderingStats: RenderingStats,
)
