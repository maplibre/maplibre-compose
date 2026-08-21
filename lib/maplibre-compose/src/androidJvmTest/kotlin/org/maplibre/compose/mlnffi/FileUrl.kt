package org.maplibre.compose.mlnffi

import java.io.File
import java.net.URI
import kotlinx.io.files.Path

internal actual fun fileUrlOf(path: Path): String = File(path.toString()).toURI().toString()

internal actual fun pathOfFileUrl(url: String): Path = Path(File(URI(url)).absolutePath)
