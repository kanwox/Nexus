package com.github.mihomo.android.data

import java.io.File
import java.security.MessageDigest

/**
 * Pure-JVM helper logic for the in-app update integrity verification.
 *
 * Everything in this object is deliberately free of Android dependencies so the
 * checksum, digest-comparison and asset-selection rules can be unit-tested on the
 * desktop JVM (the project has no Robolectric setup, only plain JUnit). The
 * download/install plumbing that uses these helpers lives in the UI/data layer and is
 * only responsible for wiring: fetching SHA256SUMS, streaming the apk to disk, and
 * asking this object to decide whether the artifact may be handed to the installer.
 */
object UpdateVerifier {

    /**
     * ABI priority used when no device-provided order is available. arm64-v8a first
     * because it is the only ABI the release currently ships native libraries for
     * (see abiFilters in app/build.gradle.kts); the others are listed so releases that
     * start shipping them later are matched in a sane order. Callers should prefer
     * Build.SUPPORTED_ABIS, which the OS already returns in best-first order.
     */
    val DEFAULT_ABI_PRIORITY = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** SHA-256 digests are 64 hex characters wide. */
    private const val DIGEST_LENGTH = 64

    /** 64 KiB copy buffer: tiny allocation for the streaming hash of a ~200 MB apk. */
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Parses the content of a GitHub release SHA256SUMS asset into
     * filename → lowercase hex digest entries.
     *
     * Why line-by-line leniency: the sums file is produced by humans and CI, and a
     * single malformed line (a comment, a wrapping artifact, an editor hiccup) must not
     * poison the whole map — bad lines are skipped, good ones still verify. A line is
     * accepted only when its first 64 characters are hex digits; everything after the
     * whitespace separator is treated as the filename (matching `sha256sum -c`
     * semantics), so filenames containing spaces survive. A single leading '*'
     * (coreutils binary-mode marker) is stripped. Digests are lowercased so later
     * comparisons never depend on the publisher's character case.
     */
    fun parseSha256Sums(text: String): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            // "64 hex chars + separator + filename" is the minimum usable shape; shorter
            // lines (comments, notes, blank lines, a bare digest) cannot be matched later.
            if (line.length < DIGEST_LENGTH + 2) return@forEach
            val digest = line.take(DIGEST_LENGTH)
            if (!isHex64(digest)) return@forEach
            val filename = line.substring(DIGEST_LENGTH)
                .trimStart(' ', '\t')
                .removePrefix("*")
                .trimEnd()
            // A digest with no filename is unusable: nothing to look the entry up by.
            if (filename.isEmpty()) return@forEach
            entries[filename] = digest.lowercase()
        }
        return entries
    }

    /**
     * Picks the release asset the current device should download.
     *
     * Why the ABI loop instead of one regex: a release page can ship several per-ABI
     * apks plus sums files, and installing the wrong architecture fails far away from
     * the cause (the app crashes at startup, or the installer rejects it with a generic
     * message). The device's own ABI order ([android.os.Build.SUPPORTED_ABIS]) is
     * already best-first, so matching it in order yields the exact artifact built for
     * this phone. When the publisher renamed the apks (or stopped embedding the ABI),
     * we degrade to the previous behavior — the first .apk asset — rather than blocking
     * the update entirely.
     */
    fun selectApkAsset(
        assets: List<Pair<String, String>>,
        supportedAbis: List<String> = DEFAULT_ABI_PRIORITY
    ): Pair<String, String>? {
        val apkAssets = assets.filter { (name, _) -> name.endsWith(".apk", ignoreCase = true) }
        // Exact, case-insensitive match against the canonical release name first.
        for (abi in supportedAbis) {
            val canonicalName = "Nexus-$abi-release.apk"
            apkAssets.firstOrNull { (name, _) -> name.equals(canonicalName, ignoreCase = true) }
                ?.let { return it }
        }
        return apkAssets.firstOrNull()
    }

    /**
     * Case-insensitive comparison of two SHA-256 digests.
     *
     * Why the hex-format guard: an empty or garbage expected value (a publisher error
     * in SHA256SUMS, a truncated sums download) must fail closed. Letting a malformed
     * digest compare equal would silently disable the entire verification step.
     */
    fun verifySha256(fileContentHashHex: String, expectedHex: String): Boolean =
        isHex64(fileContentHashHex) && isHex64(expectedHex) &&
            fileContentHashHex.equals(expectedHex, ignoreCase = true)

    /**
     * SHA-256 of an in-memory byte array as lowercase hex. Used as the test oracle for
     * [sha256HexOfFile]; the real download path hashes from the file so a ~200 MB apk
     * never sits in the heap.
     */
    fun sha256HexOfBytes(bytes: ByteArray): String = toHex(sha256().digest(bytes))

    /**
     * Streams [file] through SHA-256 without ever holding more than one buffer chunk in
     * memory, returning the digest as lowercase hex, or null when the file cannot be
     * read. Streaming is a hard requirement here: the update apk can approach the
     * 200 MB cap and must not be loaded whole into memory just to hash it.
     */
    fun sha256HexOfFile(file: File): String? = runCatching {
        val digest = sha256()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        toHex(digest.digest())
    }.getOrNull()

    /**
     * Compares the signing certificates of the installed app against the downloaded
     * archive.
     *
     * Why sets of digests instead of pairwise array comparison: certificates come back
     * in no guaranteed order (multi-signer apks), and ByteArray equality in a List is
     * identity-based — digesting each certificate to its hex SHA-256 makes the
     * comparison order-insensitive and content-based. Either side being empty means the
     * certificates could not be extracted at all, which must fail closed rather than
     * pass an unknown package through.
     */
    fun signaturesMatch(current: List<ByteArray>, incoming: List<ByteArray>): Boolean {
        if (current.isEmpty() || incoming.isEmpty()) return false
        val currentDigests = current.map { sha256HexOfBytes(it) }.toSet()
        val incomingDigests = incoming.map { sha256HexOfBytes(it) }.toSet()
        return currentDigests == incomingDigests
    }

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun isHex64(value: String): Boolean =
        value.length == DIGEST_LENGTH && value.all { ch ->
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
        }

    private fun toHex(bytes: ByteArray): String {
        // Hand-rolled hex encoding: String.format would work but drags in the default
        // locale and allocates a formatter per byte pair just to emit 64 characters.
        val digits = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(result)
    }
}

/**
 * Outcome of the in-app update download pipeline.
 *
 * Why a sealed class instead of the previous nullable File: the UI must distinguish
 * three states that need different copy — success with verification, success *without*
 * verification (the release shipped no usable checksums), and a failure whose reason is
 * shown to the user. A null silently merged all three and swallowed the cause.
 */
sealed class UpdateDownloadResult {
    /** Downloaded and (when [verified]) hash-checked apk, ready for the pre-install gate. */
    data class Success(val file: File, val verified: Boolean) : UpdateDownloadResult()

    /** [reason] is an already-localized user-facing sentence for the failure toast. */
    data class Failure(val reason: String) : UpdateDownloadResult()
}
