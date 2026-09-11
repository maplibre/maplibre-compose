package org.maplibre.compose.style

import org.maplibre.compose.expressions.ast.FontLiteral

/**
 * Registers font files referenced by compiled expressions and shares one registration between every
 * reference to the same file under the same name.
 *
 * One name maps to one file in a loaded style. When references register different files under one
 * name, the file of the most recent acquisition still held is the one the style declares, so a font
 * that changes file under a fixed name reaches the engine as soon as the new reference compiles.
 */
internal class FontManager(private val node: StyleNode) {
  /** Every held acquisition per name, oldest first, one entry per acquisition. */
  private val acquisitions = linkedMapOf<String, MutableList<StyleFontDefinition>>()

  internal val desiredFonts: List<StyleFontDefinition>
    get() = acquisitions.values.map { it.last() }

  internal fun acquire(font: FontLiteral): List<String> {
    val definition = StyleFontDefinition(font.name, font.file)
    val held = acquisitions.getOrPut(definition.name, ::mutableListOf)
    val declared = held.lastOrNull()
    held.add(definition)
    if (declared != definition) node.scheduleApplyChanges()
    return font.stack
  }

  internal fun release(font: FontLiteral) {
    val definition = StyleFontDefinition(font.name, font.file)
    val held = acquisitions.getValue(definition.name)
    val index = held.lastIndexOf(definition)
    check(index >= 0) { "Font '${definition.name}' was released more often than acquired" }
    val declared = held.last()
    held.removeAt(index)
    if (held.isEmpty()) acquisitions.remove(definition.name)
    if (held.lastOrNull() != declared) node.scheduleApplyChanges()
  }
}
