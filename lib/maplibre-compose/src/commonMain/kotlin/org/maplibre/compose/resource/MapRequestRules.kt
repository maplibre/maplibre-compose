package org.maplibre.compose.resource

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.resource.internal.UrlGlob
import org.maplibre.compose.resource.internal.appendQuery
import org.maplibre.compose.resource.internal.encodeQuery
import org.maplibre.compose.util.formatToString

/**
 * A request interceptor that adds fixed query parameters and HTTP headers to the requests whose URL
 * matches a pattern.
 *
 * Use it instead of writing a [MapRequestInterceptor] when the values stay the same while the
 * runtime is open, such as an API key. The rules apply without calling your code for each request,
 * and work the same on every platform.
 *
 * Query parameter rules and header rules are two separate lists. In each list, only the first rule
 * that matches a request applies; a request that no rule matches is unchanged. Header rules match
 * the URL after the query parameter rules change it.
 *
 * Match only URLs on your own server, so that a key or token does not go to the other servers that
 * a style loads resources from.
 *
 * @property queryParameterRules The rules whose [MapRequestRule.values] are query parameters.
 * @property headerRules The rules whose [MapRequestRule.values] are HTTP headers.
 */
@Immutable
public data class MapRequestRules
private constructor(
  public val queryParameterRules: List<MapRequestRule>,
  public val headerRules: List<MapRequestRule>,
) : MapRequestInterceptor {

  /** Edits [from]; the rules that [block] adds follow the rules of [from]. */
  public constructor(
    from: MapRequestRules = Empty,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder,
  ) : this(builder.queryParameterRules.toList(), builder.headerRules.toList())

  /**
   * Returns [MapResourceRequest.url] with the parameters of the first matching query parameter
   * rule, or null when no rule matches.
   */
  override fun rewriteUrl(request: MapResourceRequest): String? =
    queryParameterRules
      .firstOrNull { it.matches(request) }
      ?.let { appendQuery(request.url, it.encodedQuery) }

  /** Returns the headers of the first matching header rule, or no headers when none matches. */
  override fun headers(request: MapResourceRequest): Map<String, String> =
    headerRules.firstOrNull { it.matches(request) }?.values ?: emptyMap()

  override fun toString(): String =
    formatToString(
      "MapRequestRules",
      "queryParameterRules" to queryParameterRules,
      "headerRules" to headerRules,
    )

  /** Adds rules in the order that they are checked. */
  @MapOptionsDsl
  public class Builder internal constructor(from: MapRequestRules) {
    internal val queryParameterRules = from.queryParameterRules.toMutableList()
    internal val headerRules = from.headerRules.toMutableList()

    /**
     * Adds a rule that appends [parameters] to the URL of each request that matches [urlPattern]
     * and [kinds].
     *
     * The parameters go at the end of the URL's query, in order and before any fragment. Parameters
     * that the URL already has stay, even ones with the same name. Pass names and values unencoded;
     * the library percent-encodes every byte except letters, digits, `-`, `.`, `_`, and `~`.
     *
     * @param urlPattern A glob that the whole URL must match. See [MapRequestRule.urlPattern].
     * @param parameters The query parameter names and values. Names must not be empty, and names
     *   and values must not contain NUL characters.
     * @param kinds The kinds of request that the rule covers, or an empty set for every kind.
     * @throws IllegalArgumentException if a name is empty, a name or value contains a NUL
     *   character, or [urlPattern] or [kinds] is invalid as [MapRequestRule] describes.
     */
    public fun addQueryParameters(
      urlPattern: String,
      parameters: Map<String, String>,
      kinds: Set<MapResourceKind> = emptySet(),
    ) {
      parameters.forEach { (name, value) ->
        require(name.isNotEmpty()) { "A query parameter name must not be empty, was '$name'" }
        // MapLibre Native reads these as C strings. The message never shows the value.
        require(NulCharacter !in name && NulCharacter !in value) {
          "The query parameter '${name.replace(NulCharacter, ' ')}' contains a NUL character"
        }
      }
      queryParameterRules += MapRequestRule(urlPattern, kinds.toSet(), parameters.toMap())
    }

    /**
     * Adds a rule that sets [headers] on each HTTP request that matches [urlPattern] and [kinds].
     *
     * @param urlPattern A glob that the whole URL must match. See [MapRequestRule.urlPattern].
     * @param headers The header names and values. A name must be an HTTP field name and must not be
     *   a header that MapLibre or the platform manages: `Accept-Encoding`, `Connection`,
     *   `Content-Length`, `Host`, `If-Modified-Since`, `If-None-Match`, `Keep-Alive`,
     *   `Proxy-Authorization`, `Proxy-Connection`, `Range`, `TE`, `Trailer`, `Transfer-Encoding`,
     *   `Upgrade`, or `User-Agent`. A value must not contain control characters other than tab.
     * @param kinds The kinds of request that the rule covers, or an empty set for every kind.
     * @throws IllegalArgumentException if a header is invalid, or [urlPattern] or [kinds] is
     *   invalid as [MapRequestRule] describes.
     */
    public fun addHeaders(
      urlPattern: String,
      headers: Map<String, String>,
      kinds: Set<MapResourceKind> = emptySet(),
    ) {
      headers.forEach { (name, value) -> requireValidHeader(name, value) }
      headerRules += MapRequestRule(urlPattern, kinds.toSet(), headers.toMap())
    }
  }

  public companion object {
    /** No rules. */
    public val Empty: MapRequestRules = MapRequestRules(emptyList(), emptyList())
  }
}

/**
 * One rule of [MapRequestRules]: the requests it matches and the values it adds to them.
 *
 * @property urlPattern A glob that the whole URL must match, compared byte by byte as UTF-8. `*`
 *   matches any characters except `/`, `**` matches any characters, and `?` matches one character
 *   except `/`. `\` makes the next character match only itself. For example, the pattern
 *   `https://tiles.example.com/` followed by `**` matches every URL on that server. A pattern can
 *   have at most 4096 parts, where a run of `*` and an escaped character each count as one part,
 *   and must not contain NUL characters.
 * @property kinds The kinds of request that the rule covers, or an empty set for every kind. Only
 *   the named kinds of [MapResourceKind] are valid.
 * @property values The query parameters or HTTP headers that the rule adds, by name.
 */
@Immutable
public data class MapRequestRule
internal constructor(
  public val urlPattern: String,
  public val kinds: Set<MapResourceKind>,
  public val values: Map<String, String>,
) {
  private val glob: UrlGlob
  internal val encodedQuery: String by lazy { encodeQuery(values) }

  init {
    require(NulCharacter !in urlPattern) { "urlPattern must not contain a NUL character" }
    val tokens = UrlGlob.tokenCount(urlPattern)
    require(tokens <= UrlGlob.MaxTokens) {
      "urlPattern must have at most ${UrlGlob.MaxTokens} parts, had $tokens"
    }
    kinds.forEach { kind ->
      require(kind in RuleKinds) { "kinds must hold only named MapResourceKind values, had $kind" }
    }
    glob = UrlGlob(urlPattern)
  }

  internal fun matches(request: MapResourceRequest): Boolean =
    (kinds.isEmpty() || request.kind in kinds) && glob.matches(request.url)

  /** Omits [values], which often hold keys and tokens. */
  override fun toString(): String =
    formatToString(
      "MapRequestRule",
      "urlPattern" to urlPattern,
      "kinds" to kinds,
      "names" to values.keys,
    )
}

/** The kinds that a rule can name: those that both engines report by name. */
internal val RuleKinds: Set<MapResourceKind> =
  setOf(
    MapResourceKind.Unknown,
    MapResourceKind.Style,
    MapResourceKind.Source,
    MapResourceKind.Tile,
    MapResourceKind.Glyphs,
    MapResourceKind.SpriteImage,
    MapResourceKind.SpriteJson,
    MapResourceKind.Image,
  )

/** Headers that MapLibre Native or the platform HTTP client sets itself. */
private val ManagedHeaders: Set<String> =
  setOf(
    "accept-encoding",
    "connection",
    "content-length",
    "host",
    "if-modified-since",
    "if-none-match",
    "keep-alive",
    "proxy-authorization",
    "proxy-connection",
    "range",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "user-agent",
  )

private const val FieldNamePunctuation = "!#$%&'*+-.^_`|~"

private const val NulCharacter = '\u0000'

private fun requireValidHeader(name: String, value: String) {
  require(
    name.isNotEmpty() &&
      name.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it in FieldNamePunctuation },
  ) {
    "A header name must be an HTTP field name, was '$name'"
  }
  require(name.lowercase() !in ManagedHeaders) {
    "MapLibre or the platform sets the header '$name' itself"
  }
  // The message names the header, never its value, which may be a secret.
  require(value.all { it == '\t' || (it >= ' ' && it != '\u007F') }) {
    "The value of the header '$name' contains a control character"
  }
}
