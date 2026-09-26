package org.maplibre.compose.metrics

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import dev.detekt.metrics.CognitiveComplexity
import dev.detekt.metrics.CyclomaticComplexity
import dev.detekt.metrics.line
import dev.detekt.metrics.linesOfCode
import dev.detekt.metrics.processors.CLOCVisitor
import dev.detekt.metrics.processors.LLOCVisitor
import dev.detekt.metrics.processors.LOCVisitor
import dev.detekt.metrics.processors.SLOCVisitor
import dev.detekt.metrics.processors.commentLinesKey
import dev.detekt.metrics.processors.linesKey
import dev.detekt.metrics.processors.logicalLinesKey
import dev.detekt.metrics.processors.sourceLinesKey
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

enum class TypeKind {
  CLASS,
  INTERFACE,
  OBJECT,
  COMPANION,
  ENUM,
  ANNOTATION,
}

data class TypeFacts(
  /** Name including enclosing types, such as `Outer.Inner`. */
  val name: String,
  val kind: TypeKind,
  /** detekt's lines of code: lines holding a token, excluding comments. */
  val lines: Int,
)

/**
 * One named function, measured the way detekt's rules measure it. Every named function counts,
 * including local functions and methods of object literals.
 */
data class FunctionFacts(
  /** Name including enclosing types, with `<anonymous>` for an object literal. */
  val name: String,
  /** 1-based line of the declaration, which tells overloads apart. */
  val line: Int,
  /** detekt's lines of code: lines holding a token, excluding comments. */
  val lines: Int,
  /** detekt's cyclomatic complexity, which is zero inside an object literal. */
  val cyclomaticComplexity: Int,
  val cognitiveComplexity: Int,
  val parameters: Int,
)

data class Import(val fqName: String, val allUnder: Boolean)

/** What one Kotlin file contributes: its syntax only, with no types resolved. */
data class FileFacts(
  val source: SourceFile,
  val packageName: String,
  val imports: List<Import>,
  val loc: Int,
  val sloc: Int,
  val lloc: Int,
  val cloc: Int,
  val cyclomaticComplexity: Int,
  val cognitiveComplexity: Int,
  val functions: List<FunctionFacts>,
  val types: List<TypeFacts>,
  /** Declarations written with the `expect` keyword. */
  val expectDeclarations: Int,
  /** Declarations written with the `actual` keyword. */
  val actualDeclarations: Int,
  val todoCount: Int,
  val suppressCount: Int,
)

private val todoPattern = Regex("""\b(TODO|FIXME|HACK|XXX)\b""")

fun analyzeFile(source: SourceFile, file: KtFile): FileFacts {
  val types = mutableListOf<TypeFacts>()

  fun visit(container: List<KtDeclaration>, prefix: String) {
    for (declaration in container) {
      val name = prefix + (declaration.name ?: "<anonymous>")
      when (declaration) {
        // An entry is a KtClassOrObject in the PSI but a value of its enum, not a type.
        is KtEnumEntry -> visit(declaration.declarations, "$name.")
        is KtClassOrObject -> {
          types += TypeFacts(name, declaration.typeKind(), declaration.linesOfCode())
          visit(declaration.declarations, "$name.")
        }
        else -> {}
      }
    }
  }
  visit(file.declarations, prefix = "")

  val modifierLists = file.collectDescendantsOfType<KtModifierList>()

  val functions =
    file
      .collectDescendantsOfType<KtNamedFunction>()
      .map { function ->
        val owners = function.parents.filterIsInstance<KtClassOrObject>().toList().asReversed()
        FunctionFacts(
          name = (owners.map { it.name ?: "<anonymous>" } + function.name).joinToString("."),
          // The node itself starts at any comment that leads the declaration.
          line = (function.nameIdentifier ?: function).node.line(file),
          lines = function.linesOfCode(),
          cyclomaticComplexity = CyclomaticComplexity.calculate(function),
          cognitiveComplexity = CognitiveComplexity.calculate(function),
          parameters = function.valueParameters.size,
        )
      }
      .sortedBy { it.line }

  val comments = file.collectDescendantsOfType<PsiComment>()
  return FileFacts(
    source = source,
    packageName = file.packageFqName.asString(),
    imports =
      file.importDirectives.mapNotNull { directive ->
        directive.importedFqName?.let { Import(it.asString(), directive.isAllUnder) }
      },
    loc = file.metric(LOCVisitor(), linesKey),
    sloc = file.metric(SLOCVisitor(), sourceLinesKey),
    lloc = file.metric(LLOCVisitor(), logicalLinesKey),
    cloc = file.metric(CLOCVisitor(), commentLinesKey),
    cyclomaticComplexity = CyclomaticComplexity.calculate(file),
    cognitiveComplexity = CognitiveComplexity.calculate(file),
    functions = functions,
    types = types,
    expectDeclarations = modifierLists.count { it.hasModifier(KtTokens.EXPECT_KEYWORD) },
    actualDeclarations = modifierLists.count { it.hasModifier(KtTokens.ACTUAL_KEYWORD) },
    todoCount = comments.sumOf { todoPattern.findAll(it.text).count() },
    suppressCount =
      file.collectDescendantsOfType<KtAnnotationEntry>().count {
        it.shortName?.asString() == "Suppress"
      },
  )
}

private fun <T : Any> KtFile.metric(visitor: KtVisitorVoid, key: Key<T>): T {
  accept(visitor)
  return checkNotNull(getUserData(key)) { "$visitor left no $key on $name" }
}

private fun KtClassOrObject.typeKind(): TypeKind =
  when {
    this is KtObjectDeclaration && isCompanion() -> TypeKind.COMPANION
    this is KtObjectDeclaration -> TypeKind.OBJECT
    this is KtClass && isInterface() -> TypeKind.INTERFACE
    this is KtClass && isEnum() -> TypeKind.ENUM
    this is KtClass && isAnnotation() -> TypeKind.ANNOTATION
    else -> TypeKind.CLASS
  }
