package com.abodenestly.shopping

import java.util.Locale

/**
 * Client twin of the SQL `name_key`: `lower(regexp_replace(trim(p), '\s+', ' ', 'g'))` (SPEC §5). Postgres `trim()`
 * strips only U+0020, so a leading or trailing tab survives the trim and then collapses to one space, exactly as in
 * `web/src/lib/nameKey.ts` and iOS `NameKey`. Lowercasing ignores the phone's locale: a Turkish "I" still keys as "i".
 */
object NameKey {
    // `\z`, not `$`: Java's `$` also matches before a final newline, which JS and SQL do not.
    private val ends = Regex("^ +| +\\z")

    // The JS `\s` set (Java's own `\s` is ASCII only), so a no-break space collapses on every platform.
    private val runs = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    fun make(name: String): String = name.replace(ends, "").replace(runs, " ").lowercase(Locale.ROOT)
}
