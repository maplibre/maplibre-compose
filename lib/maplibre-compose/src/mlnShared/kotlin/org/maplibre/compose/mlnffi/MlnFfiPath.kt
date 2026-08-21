package org.maplibre.compose.mlnffi

import kotlinx.io.files.Path

/**
 * Resolves [path] to one absolute lexical form, so that two spellings of the same location compare
 * equal. A path with no file behind it normalizes like any other.
 *
 * `SystemFileSystem.resolve` answers the same question against the filesystem, and fails when the
 * path names nothing, so it cannot serve a cache database that this call is about to create.
 */
internal expect fun normalizeMlnFfiPath(path: Path): Path
