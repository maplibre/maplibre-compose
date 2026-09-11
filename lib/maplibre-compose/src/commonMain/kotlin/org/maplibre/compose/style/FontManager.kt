package org.maplibre.compose.style

import org.maplibre.compose.expressions.ast.FontLiteral

/**
 * Registers font files referenced by compiled expressions and shares one registration between every
 * reference to the same file under the same name.
 *
 * One name maps to one file in a loaded style. When two references register different files under
 * one name, the most recently acquired file is the one the style declares, so a font that changes
 * file under a fixed name reaches the engine as soon as the new reference compiles.
 */
internal class FontManager(private val node: StyleNode) {
  private val counter = ReferenceCounter<StyleFontDefinition>()

  /** Active registrations per name, oldest first. */
  private val registrations = linkedMapOf<String, MutableList<StyleFontDefinition>>()

  internal val desiredFonts: List<StyleFontDefinition>
    get() = registrations.values.map { it.last() }

  internal fun acquire(font: FontLiteral): List<String> {
    val definition = StyleFontDefinition(font.name, font.file)
    counter.increment(definition) {}
    val active = registrations.getOrPut(definition.name, ::mutableListOf)
    if (active.lastOrNull() != definition) {
      active.remove(definition)
      active.add(definition)
      node.scheduleApplyChanges()
    }
    return font.stack
  }

  internal fun release(font: FontLiteral) {
    val definition = StyleFontDefinition(font.name, font.file)
    counter.decrement(definition) {
      val active = registrations.getValue(definition.name)
      active.remove(definition)
      if (active.isEmpty()) registrations.remove(definition.name)
      node.scheduleApplyChanges()
    }
  }
}
