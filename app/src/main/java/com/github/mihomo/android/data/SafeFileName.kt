package com.github.mihomo.android.data

import java.io.File

/**
 * Guards file names that are derived from stored or restored metadata.
 *
 * Profile ids, script file names and backup file names are persisted in JSON/SharedPreferences
 * and — for backups — arrive from a user-picked file. Interpolating such a value straight
 * into a path (`filesDir/profiles/$id.yaml`) turns any embedded separator or `..` segment
 * into a path traversal, letting a crafted value write outside the intended directory.
 *
 * Every name that is not a plain, single-segment file name is rejected instead.
 */
internal object SafeFileName {
    /** Characters that are legal in the identifiers this app mints (UUIDs, script file names). */
    private val SAFE_PATTERN = Regex("^[A-Za-z0-9._-]+$")

    /** Maximum length keeps the name well below any filesystem limit. */
    private const val MAX_LENGTH = 200

    /**
     * Returns [name] when it is a safe single path segment, or `null` otherwise.
     *
     * A safe name is non-empty, at most [MAX_LENGTH] characters long, contains only
     * letters, digits, dot, underscore and hyphen, and is not `.` or `..`.
     */
    fun sanitize(name: String?): String? {
        val trimmed = name?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        if (trimmed == "." || trimmed == "..") return null
        if (!SAFE_PATTERN.matches(trimmed)) return null
        return trimmed
    }

    /**
     * Resolves [name] inside [base] safely, or returns `null` when the name would escape
     * the directory. The returned file is guaranteed to be a direct child of [base].
     */
    fun resolveChild(base: File, name: String?): File? {
        val safe = sanitize(name) ?: return null
        val child = File(base, safe)
        // Belt-and-braces: even with a sanitized name, verify the canonical parent.
        val parent = child.parentFile ?: return null
        return if (parent.canonicalFile == base.canonicalFile) child else null
    }
}
