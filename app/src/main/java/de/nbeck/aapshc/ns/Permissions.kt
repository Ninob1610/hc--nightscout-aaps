package de.nbeck.aapshc.ns

/**
 * Prüft Nightscout-Rechte (Shiro-Syntax) so, wie der Server sie auswertet:
 * `*` passt auf alles, Kommalisten auf jedes Element, und ein kürzeres Muster
 * schließt alle Unterrechte ein (`api:treatments` ⇒ `api:treatments:update`).
 */
object Permissions {
    const val CREATE = "api:treatments:create"
    const val UPDATE = "api:treatments:update"
    const val DELETE = "api:treatments:delete"

    /** Nur für die Auswertung „Sport und Glukose“ (CGM-Werte lesen). */
    const val ENTRIES_READ = "api:entries:read"

    /** Für den Abend-Kontext der Nacht-Auswertung (Bolus, Kohlenhydrate, temporäre Ziele lesen). */
    const val TREATMENTS_READ = "api:treatments:read"

    /** Ohne diese Rechte kann die App nicht sinnvoll synchronisieren. */
    val REQUIRED = listOf(CREATE, UPDATE, DELETE)

    fun implies(pattern: String, required: String): Boolean {
        val p = pattern.split(':')
        val r = required.split(':')
        if (p.size > r.size) return false
        return p.indices.all { i -> p[i] == "*" || r[i] in p[i].split(',') }
    }

    fun granted(patterns: Collection<String>, required: String): Boolean =
        patterns.any { implies(it.trim(), required) }

    fun missing(patterns: Collection<String>): List<String> = REQUIRED.filterNot { granted(patterns, it) }
}
