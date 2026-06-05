package com.lazydevs.wristotle.mcp

/**
 * Most MCP servers expect `Authorization: <scheme> <credentials>`. When
 * users paste just the raw token, the request goes out as
 * `Authorization: <token>` and the server returns 401 with a confusing
 * "Unauthorized" body — the failure mode that prompted this check.
 *
 * Returns true if [value] starts with one of the standard HTTP auth
 * schemes followed by a space. Used by the Add / Edit dialog to soft-warn
 * before saving a value that's almost certainly a bare token.
 *
 * Empty / blank values return true (= no warning) so we don't pester
 * users who legitimately want no auth header.
 */
internal fun looksLikeFullAuthHeader(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true
    val firstWord = trimmed.substringBefore(' ', missingDelimiterValue = "")
        .lowercase()
    return firstWord in KNOWN_SCHEMES
}

private val KNOWN_SCHEMES = setOf("bearer", "basic", "token", "digest", "api-key", "apikey")
