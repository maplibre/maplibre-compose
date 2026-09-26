package org.maplibre.compose.metrics

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import dev.detekt.metrics.CognitiveComplexity
import dev.detekt.metrics.CyclomaticComplexity
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
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

enum class Visibility {
  PUBLIC,
  PROTECTED,
  INTERNAL,
  PRIVATE,
}

enum class TypeKind {
  CLASS,
  INTERFACE,
  OBJECT,
  COMPANION,
  ENUM,
  ANNOTATION,
}

/** One named declaration: a type, function, property, constructor, or type alias. */
data class Declaration(
  val name: String,
  val kind: String,
  val visibility: Visibility,
  /** Public and enclosed only by public types. */
  val isEffectivelyPublic: Boolean,
  val isOverride: Boolean,
  val isExpect: Boolean,
  val isActual: Boolean,
  val hasKDoc: Boolean,
)

data class FunctionFacts(
  val name: String,
  val lines: Int,
  val cyclomaticComplexity: Int,
  val cognitiveComplexity: Int,
  val parameters: Int,
  val nestingDepth: Int,
  val isEffectivelyPublic: Boolean,
)

data class TypeFacts(
  /** Name including enclosing types, such as `Outer.Inner`. */
  val name: String,
  val kind: TypeKind,
  val isAbstract: Boolean,
  val isSealed: Boolean,
  val isFunInterface: Boolean,
  /** A JS binding: `external interface`. */
  val isExternal: Boolean,
  val isExpect: Boolean,
  val isActual: Boolean,
  val lines: Int,
  /** Effectively public members that do not override a supertype member. */
  val publicMembers: Int,
  /** Listed supertypes as written, with type arguments removed. [TypeIndex] resolves them. */
  val supertypes: List<String>,
  val isEffectivelyPublic: Boolean,
) {
  /** The enclosing type's nested name, or null at the top level. */
  val enclosingType: String?
    get() = name.substringBeforeLast('.', "").ifEmpty { null }
}

data class TypeAliasFacts(
  /** Name including enclosing types. */
  val name: String,
  /** The aliased type as written, with type arguments removed. */
  val target: String,
)

data class Import(val fqName: String, val allUnder: Boolean, val alias: String? = null)

/** A type name as written somewhere in a file, with the nested type it was written inside. */
data class TypeReference(val text: String, val enclosingType: String?)

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
  val declarations: List<Declaration>,
  val functions: List<FunctionFacts>,
  val types: List<TypeFacts>,
  val typeAliases: List<TypeAliasFacts>,
  /** The supertypes of every `object : X` literal. */
  val anonymousImplementations: List<TypeReference>,
  /** Calls per callee, so `Listener { }` can count as implementing a fun interface. */
  val calls: Map<TypeReference, Int>,
  val todoCount: Int,
  val suppressCount: Int,
)

private val todoPattern = Regex("""\b(TODO|FIXME|HACK|XXX)\b""")

fun analyzeFile(source: SourceFile, file: KtFile): FileFacts {
  val lines = LineIndex(file.text)
  val declarations = mutableListOf<Declaration>()
  val functions = mutableListOf<FunctionFacts>()
  val types = mutableListOf<TypeFacts>()
  val typeAliases = mutableListOf<TypeAliasFacts>()

  fun visit(container: List<KtDeclaration>, enclosingPublic: Boolean, prefix: String) {
    for (declaration in container) {
      // An `init` block has no name and cannot be documented or referenced.
      if (declaration is KtAnonymousInitializer) continue
      val visibility = declaration.visibility()
      val effectivelyPublic =
        enclosingPublic && (visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED)
      val name = prefix + (declaration.name ?: "<anonymous>")
      declarations +=
        Declaration(
          name = name,
          kind = declaration.kindName(),
          visibility = visibility,
          isEffectivelyPublic = effectivelyPublic,
          isOverride = declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD),
          isExpect = declaration.hasModifier(KtTokens.EXPECT_KEYWORD),
          isActual = declaration.hasModifier(KtTokens.ACTUAL_KEYWORD),
          hasKDoc = declaration.docComment != null,
        )
      when (declaration) {
        is KtNamedFunction ->
          functions +=
            FunctionFacts(
              name = name,
              lines = lines.spanOf(declaration),
              cyclomaticComplexity = CyclomaticComplexity.calculate(declaration),
              cognitiveComplexity = CognitiveComplexity.calculate(declaration),
              parameters = declaration.valueParameters.size,
              nestingDepth = nestingDepth(declaration),
              isEffectivelyPublic = effectivelyPublic,
            )
        // An entry is a KtClassOrObject in the PSI but a value of its enum, not a type.
        is KtEnumEntry -> visit(declaration.declarations, effectivelyPublic, "$name.")
        is KtClassOrObject -> {
          val members = declaration.declarations
          types +=
            TypeFacts(
              name = name,
              kind = declaration.typeKind(),
              isAbstract =
                declaration.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                  declaration.hasModifier(KtTokens.SEALED_KEYWORD) ||
                  (declaration is KtClass && declaration.isInterface()),
              isSealed = declaration.hasModifier(KtTokens.SEALED_KEYWORD),
              isFunInterface = declaration.hasModifier(KtTokens.FUN_KEYWORD),
              isExternal = declaration.hasModifier(KtTokens.EXTERNAL_KEYWORD),
              isExpect = declaration.hasModifier(KtTokens.EXPECT_KEYWORD),
              isActual = declaration.hasModifier(KtTokens.ACTUAL_KEYWORD),
              lines = lines.spanOf(declaration),
              publicMembers =
                members.count {
                  effectivelyPublic &&
                    it !is KtAnonymousInitializer &&
                    it.visibility() == Visibility.PUBLIC &&
                    !it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
                },
              supertypes = declaration.supertypeNames(),
              isEffectivelyPublic = effectivelyPublic,
            )
          visit(members, effectivelyPublic, "$name.")
        }
        is KtTypeAlias ->
          declaration.getTypeReference()?.text?.let {
            typeAliases += TypeAliasFacts(name, it.typeName())
          }
        else -> {}
      }
    }
  }
  visit(file.declarations, enclosingPublic = true, prefix = "")

  val comments = file.collectDescendantsOfType<PsiComment>()
  return FileFacts(
    source = source,
    packageName = file.packageFqName.asString(),
    imports =
      file.importDirectives.mapNotNull { directive ->
        directive.importedFqName?.let {
          Import(it.asString(), directive.isAllUnder, directive.aliasName)
        }
      },
    loc = file.metric(LOCVisitor(), linesKey),
    sloc = file.metric(SLOCVisitor(), sourceLinesKey),
    lloc = file.metric(LLOCVisitor(), logicalLinesKey),
    cloc = file.metric(CLOCVisitor(), commentLinesKey),
    cyclomaticComplexity = CyclomaticComplexity.calculate(file),
    cognitiveComplexity = CognitiveComplexity.calculate(file),
    declarations = declarations,
    functions = functions,
    types = types,
    typeAliases = typeAliases,
    anonymousImplementations =
      file.collectDescendantsOfType<KtObjectLiteralExpression>().flatMap { literal ->
        val enclosing = literal.enclosingTypeName()
        literal.objectDeclaration.supertypeNames().map { TypeReference(it, enclosing) }
      },
    calls =
      file
        .collectDescendantsOfType<KtCallExpression>()
        .mapNotNull { call ->
          call.calleeName()?.let { TypeReference(it, call.enclosingTypeName()) }
        }
        .groupingBy { it }
        .eachCount(),
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

private fun KtDeclaration.visibility(): Visibility =
  when {
    hasModifier(KtTokens.PRIVATE_KEYWORD) -> Visibility.PRIVATE
    hasModifier(KtTokens.INTERNAL_KEYWORD) -> Visibility.INTERNAL
    hasModifier(KtTokens.PROTECTED_KEYWORD) -> Visibility.PROTECTED
    else -> Visibility.PUBLIC
  }

private fun KtDeclaration.kindName(): String =
  when (this) {
    is KtEnumEntry -> "entry"
    is KtClassOrObject -> typeKind().name.lowercase()
    is KtNamedFunction -> "function"
    is KtProperty -> "property"
    is KtSecondaryConstructor -> "constructor"
    is KtTypeAlias -> "typealias"
    else -> javaClass.simpleName.removePrefix("Kt").lowercase()
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

/** `org.example.Outer.Inner<T>?` becomes `org.example.Outer.Inner`. */
internal fun String.typeName(): String = substringBefore('<').trimEnd('?', ' ').trim()

private fun KtClassOrObject.supertypeNames(): List<String> = superTypeListEntries.mapNotNull {
  it.typeReference?.text?.typeName()
}

/** The nested name of the named type that contains [this], or null outside any type. */
private fun PsiElement.enclosingTypeName(): String? {
  val names =
    parents.filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toList().asReversed()
  return names.takeIf { it.isNotEmpty() }?.joinToString(".")
}

private val qualifiedName = Regex("""[A-Za-z_][\w.]*""")

/** The callee as written, keeping a qualifier such as `Outer.Callback { }`. */
private fun KtCallExpression.calleeName(): String? {
  val callee = calleeExpression?.text ?: return null
  val qualified = parent as? KtDotQualifiedExpression
  val receiver = qualified?.receiverExpression?.text
  return if (
    qualified?.selectorExpression === this && receiver != null && qualifiedName.matches(receiver)
  ) {
    "$receiver.$callee"
  } else {
    callee
  }
}

/** Blocks nested inside the function body, so a body with no nested blocks scores zero. */
private fun nestingDepth(function: KtNamedFunction): Int {
  val body = function.bodyBlockExpression ?: return 0
  return body.collectDescendantsOfType<KtBlockExpression>().maxOfOrNull { block ->
    block.parents.takeWhile { it !== function }.count { it is KtBlockExpression }
  } ?: 0
}

/** Maps text offsets to 1-based line numbers. */
class LineIndex(text: String) {
  private val lineStarts: IntArray =
    intArrayOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }.toIntArray()

  fun lineOf(offset: Int): Int {
    val index = lineStarts.binarySearch(offset)
    return if (index >= 0) index + 1 else -index - 1
  }

  /** Lines from the first token of [element] to its last, excluding comments that lead it. */
  fun spanOf(element: PsiElement): Int {
    var first = element.firstChild
    while (first != null && (first is PsiComment || first is PsiWhiteSpace)) first =
      first.nextSibling
    val start = (first ?: element).textRange.startOffset
    val end = element.textRange.endOffset
    return lineOf(maxOf(start, end - 1)) - lineOf(start) + 1
  }
}
