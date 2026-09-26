package org.maplibre.compose.metrics

sealed interface Resolution {
  /** The qualified name of a type declared in the scanned code. */
  data class Resolved(val fqName: String) : Resolution

  /** Several star imports supply a type of this name. */
  data class Ambiguous(val candidates: List<String>) : Resolution

  /** Not declared in the scanned code, or not findable from syntax alone. */
  data object External : Resolution
}

/**
 * Resolves type names as written to the types declared in the scanned files, using Kotlin's lexical
 * rules: enclosing types, then explicit imports, then the file's package, then star imports. A type
 * alias resolves to the type it aliases.
 */
class TypeIndex(files: List<FileFacts>) {
  private val types: Set<String> =
    files.flatMap { file -> file.types.map { file.qualify(it.name) } }.toSet()
  private val aliases: Map<String, Pair<FileFacts, TypeAliasFacts>> =
    files.flatMap { file -> file.typeAliases.map { file.qualify(it.name) to (file to it) } }.toMap()

  fun resolve(reference: TypeReference, file: FileFacts): Resolution =
    resolve(reference, file, depth = 0)

  private fun resolve(reference: TypeReference, file: FileFacts, depth: Int): Resolution {
    val text = reference.text
    if (text in types) return Resolution.Resolved(text)
    val head = text.substringBefore('.')
    val rest = text.substringAfter('.', "")
    val resolved =
      when (val resolution = resolveHead(head, reference.enclosingType, file)) {
        is Resolution.Resolved ->
          if (rest.isEmpty()) resolution else lookup("${resolution.fqName}.$rest")
        is Resolution.Ambiguous ->
          if (rest.isEmpty()) resolution
          else ambiguous(resolution.candidates.map { "$it.$rest" }.filter { it in types })
        Resolution.External -> resolution
      }
    val alias = (resolved as? Resolution.Resolved)?.let { aliases[it.fqName] } ?: return resolved
    if (depth >= MAX_ALIAS_DEPTH) return Resolution.External
    val (aliasFile, facts) = alias
    val enclosing = facts.name.substringBeforeLast('.', "").ifEmpty { null }
    return resolve(TypeReference(facts.target, enclosing), aliasFile, depth + 1)
  }

  private fun resolveHead(head: String, enclosingType: String?, file: FileFacts): Resolution {
    var enclosing = enclosingType
    while (enclosing != null) {
      lookup(file.qualify("$enclosing.$head")).let { if (it is Resolution.Resolved) return it }
      enclosing = enclosing.substringBeforeLast('.', "").ifEmpty { null }
    }
    val explicit =
      file.imports.firstOrNull {
        !it.allUnder && (it.alias ?: it.fqName.substringAfterLast('.')) == head
      }
    if (explicit != null) return lookup(explicit.fqName)
    lookup(file.qualify(head)).let { if (it is Resolution.Resolved) return it }
    return ambiguous(
      file.imports.filter { it.allUnder }.map { "${it.fqName}.$head" }.filter { isDeclared(it) }
    )
  }

  /** The resolution of a name that [candidates] declarations could supply. */
  private fun ambiguous(candidates: List<String>): Resolution =
    when (candidates.size) {
      0 -> Resolution.External
      1 -> Resolution.Resolved(candidates.single())
      else -> Resolution.Ambiguous(candidates)
    }

  private fun isDeclared(fqName: String) = fqName in types || fqName in aliases

  private fun lookup(fqName: String): Resolution =
    if (isDeclared(fqName)) Resolution.Resolved(fqName) else Resolution.External

  private companion object {
    const val MAX_ALIAS_DEPTH = 4
  }
}

/** The qualified name of a type declared as [nestedName] in this file's package. */
fun FileFacts.qualify(nestedName: String): String =
  if (packageName.isEmpty()) nestedName else "$packageName.$nestedName"
