package org.maplibre.compose.mlnffi

import java.nio.file.Paths
import kotlinx.io.files.Path

internal actual fun normalizeMlnFfiPath(path: Path): Path =
  Path(Paths.get(path.toString()).toAbsolutePath().normalize().toString())
