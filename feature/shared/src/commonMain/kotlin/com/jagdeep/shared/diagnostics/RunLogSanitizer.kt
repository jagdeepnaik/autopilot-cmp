package com.jagdeep.shared.diagnostics

/**
 * Enforces the run-log privacy table when a line is written, not only before it is uploaded:
 *
 * - a string under a key that names forbidden content (addresses, titles, page/question/option/answer
 *   text, typed values, passwords, tokens, cookies, account or user names, paths, course and module
 *   names) is dropped, unless the key ends in a safe suffix such as `Key`, `Hash`, `Count` or `Index`
 *   (numbers and yes/no values are kept: they can't carry text);
 * - strings become one line of at most 200 characters (labels 40), with URLs, e-mail addresses,
 *   bearer tokens and JWTs, user folders and [personal] values (the computer's user name and home
 *   folder) replaced by placeholders;
 * - values are numbers, booleans, null or strings; anything else becomes its sanitised text.
 */
class RunLogSanitizer(personal: List<String> = emptyList()) {
    private val personal = personal.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }

    /** A safe JSON-ready map: keys checked, values reduced to primitives. */
    fun data(values: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in values) {
            if (result.size >= MAX_KEYS) break
            if (!KEY.matches(key)) continue
            val clean = value(key, value)
            // A number or a yes/no can't carry text, so only strings need a safe key.
            if (clean is String && !mayHoldText(key)) continue
            result[key] = clean
        }
        return result
    }

    fun value(key: String, value: Any?): Any? = when (value) {
        null -> null
        is Boolean -> value
        is Int, is Long, is Short, is Byte -> value
        is Double -> value.takeIf { it.isFinite() }
        is Float -> value.takeIf { it.isFinite() }?.toDouble()
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is Enum<*> -> text(value.name, maxLength(key))
        else -> text(value.toString(), maxLength(key))
    }

    /** One safe line of text. */
    fun text(raw: String, max: Int = MAX_STRING): String {
        var value = raw
        personal.forEach { value = value.replace(it, "[user]", ignoreCase = true) }
        value = URL.replace(value, "[url]")
        value = WWW.replace(value, "[url]")
        value = EMAIL.replace(value, "[email]")
        value = BEARER.replace(value, "Bearer [token]")
        value = JWT.replace(value, "[token]")
        value = USER_FOLDER.replace(value) { it.groupValues[1] + "[user]" }
        value = WHITESPACE.replace(value, " ").trim()
        return if (value.length <= max) value else value.take(max - 1) + "…"
    }

    private fun maxLength(key: String) = if (key == "label" || key.endsWith("Label")) MAX_LABEL else MAX_STRING

    companion object {
        const val MAX_STRING = 200
        const val MAX_LABEL = 40
        private const val MAX_KEYS = 40

        private val KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
        private val FORBIDDEN = listOf(
            "url", "uri", "href", "src", "link", "title", "text", "html", "content", "question", "option", "answer",
            "password", "passwd", "secret", "email", "mail", "token", "cookie", "typed", "input", "value",
            "path", "folder", "dir", "file", "user", "account", "profile", "person", "course", "module", "chapter", "lesson",
        )
        /** A key ending in one of these describes forbidden content without containing it (questionKey, textLength). */
        private val SAFE_SUFFIXES = listOf(
            "Key", "Keys", "Hash", "Count", "Index", "Id", "Ms", "Millis", "Length", "Size", "Bytes", "Seconds",
            "Kind", "State", "Present", "Found", "Known", "Changed", "Set", "Ready", "Matched", "Number",
        )

        /** Whether a string may be written under [key]. */
        fun mayHoldText(key: String): Boolean {
            if (!KEY.matches(key)) return false
            if (SAFE_SUFFIXES.any { key.endsWith(it) && key.length > it.length }) return true
            val lower = key.lowercase()
            return FORBIDDEN.none { lower.contains(it) }
        }

        private val URL = Regex("(?i)\\b[a-z][a-z0-9+.-]{1,20}://[^\\s\"'<>]*")
        private val WWW = Regex("(?i)\\bwww\\.[^\\s\"'<>]+")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val BEARER = Regex("(?i)\\bbearer\\s+\\S+")
        private val JWT = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val USER_FOLDER = Regex("(?i)((?:/Users/|/home/|[A-Z]:[\\\\/]Users[\\\\/]|\\\\Users\\\\))[^/\\\\\\s\"']+")
        private val WHITESPACE = Regex("\\s+")

        /** An error's class and the first line of its message; the writer sanitises both again. */
        fun errorData(error: Throwable): Map<String, Any?> = mapOf(
            "errorClass" to (error::class.simpleName ?: "Error"),
            "message" to error.message?.lineSequence()?.firstOrNull()?.take(MAX_STRING),
        )
    }
}
