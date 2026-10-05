package com.androidcam.prusa

/**
 * Extracts a Prusa Connect camera token (20 alphanumeric chars) from a QR
 * code payload. The exact payload format Prusa Connect encodes is not
 * documented, so this is deliberately lenient:
 *
 * 1. The payload is the bare token.
 * 2. A JSON `"token"` field.
 * 3. A URL query parameter value.
 * 4. A URL path segment.
 * 5. Fallback: the first 20-char alphanumeric run anywhere in the payload.
 *
 * Pure string logic (no Android APIs) so it is unit-testable on the JVM.
 */
object QrTokenParser {
    private val EXACT = Regex("^[A-Za-z0-9]{20}$")
    private val JSON_TOKEN = Regex("\"token\"\\s*:\\s*\"([A-Za-z0-9]{20})\"")
    private val CANDIDATE = Regex("[A-Za-z0-9]{20}")

    /** @return the extracted token, or null if no plausible token is found. */
    fun extractToken(payload: String): String? {
        val p = payload.trim()
        if (p.isEmpty()) return null
        if (EXACT.matches(p)) return p

        JSON_TOKEN.find(p)?.let { return it.groupValues[1] }

        if (p.startsWith("http://") || p.startsWith("https://")) {
            // Query parameter values (?key=value&...).
            p
                .substringAfter('?', "")
                .split('&')
                .map { it.substringAfter('=', "") }
                .firstOrNull { EXACT.matches(it) }
                ?.let { return it }
            // Path segments (/camera/<token>/...).
            p
                .substringAfter('/', "")
                .substringBefore('?')
                .split('/')
                .firstOrNull { EXACT.matches(it) }
                ?.let { return it }
        }

        // Last resort: any 20-char alphanumeric run.
        return CANDIDATE.find(p)?.value
    }
}
