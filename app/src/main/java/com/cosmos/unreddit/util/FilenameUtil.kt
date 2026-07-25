package com.cosmos.unreddit.util

import java.util.Locale

/**
 * Helpers to build file names that are valid on Android, Windows, macOS and Unix.
 *
 * The rules applied here are the intersection of the four platforms:
 *
 * - Windows forbids `\ / : * ? " < > |` and control characters, ignores trailing dots and spaces,
 *   and reserves a set of legacy device names (`CON`, `NUL`, `COM1`…).
 * - Android enforces the FAT rules on external storage, whatever the actual file system: the
 *   character set is the one of `android.os.FileUtils.isValidFatFilenameChar`, which is the Windows
 *   set plus `DEL` and the C1 controls, and `MediaStore` runs the same sanitization on
 *   `DISPLAY_NAME`. A name starting with a dot is hidden from the media scanner, so the download
 *   would never show up in the gallery.
 * - macOS (HFS+/APFS) forbids `:` and `/`.
 * - Unix only forbids `/` and NUL, but limits each path component to 255 bytes.
 */
object FilenameUtil {

    /**
     * Longest single path component allowed by ext4/APFS (bytes) and NTFS (UTF-16 units).
     *
     * Android trims to the same value in bytes, because external storage is reached through a FUSE
     * layer backed by ext4.
     */
    private const val MAX_COMPONENT_LENGTH = 255

    /**
     * Bytes left free for what callers append to the base name: the `_yyyyMMdd_HHmmss` timestamp,
     * the `_video` / `_audio` suffix and the extension.
     */
    private const val RESERVED_SUFFIX_LENGTH = 32

    /**
     * Upper bound for the user-controlled part of a file name, in UTF-8 bytes.
     */
    private const val MAX_BASE_BYTES = MAX_COMPONENT_LENGTH - RESERVED_SUFFIX_LENGTH

    /**
     * Upper bound for the user-controlled part of a file name, in characters.
     *
     * Reddit user names are at most 20 characters, so this only ever kicks in for unexpected
     * input. Together with [MAX_BASE_BYTES] it keeps the assembled name below
     * [MAX_COMPONENT_LENGTH] on every platform, including for names made of multi-byte characters.
     */
    const val MAX_BASE_LENGTH = 100

    private const val REPLACEMENT = '_'

    private val ILLEGAL_CHARACTERS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /**
     * Legacy MS-DOS device names. Windows rejects them with or without an extension.
     */
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Turns [name] into a string that can be used as the base of a file name on any platform, or
     * returns null when nothing usable is left.
     */
    fun sanitize(name: String?, maxLength: Int = MAX_BASE_LENGTH): String? {
        if (name.isNullOrBlank()) return null

        val sanitized = name
            .mapIndexed { index, character ->
                when {
                    character in ILLEGAL_CHARACTERS -> REPLACEMENT
                    // Covers NUL, DEL and the C1 range, all rejected by Android
                    character.isISOControl() -> REPLACEMENT
                    // Would be written to disk as '?', which is itself an illegal character
                    name.isUnpairedSurrogate(index) -> REPLACEMENT
                    else -> character
                }
            }
            .joinToString("")
            .trim()
            // A leading dot hides the file from the Android media scanner, and it is what turns
            // "." and ".." into directory references rather than names
            .trimStart('.', ' ')
            .truncate(maxLength)
            // Windows silently drops trailing dots and spaces, which would defeat the truncation
            .trimEnd('.', ' ')

        return when {
            sanitized.isEmpty() -> null
            sanitized.isReserved() -> "$REPLACEMENT$sanitized"
            else -> sanitized
        }
    }

    /**
     * Truncates to [maxLength] characters without splitting a surrogate pair, then to
     * [MAX_BASE_BYTES] bytes so the result stays valid on file systems that count bytes rather
     * than characters.
     */
    private fun String.truncate(maxLength: Int): String {
        var truncated = if (length > maxLength) {
            val end = if (Character.isHighSurrogate(this[maxLength - 1])) {
                maxLength - 1
            } else {
                maxLength
            }
            substring(0, end)
        } else {
            this
        }

        while (truncated.toByteArray(Charsets.UTF_8).size > MAX_BASE_BYTES) {
            truncated = truncated.dropLast(1)

            if (truncated.isNotEmpty() && Character.isHighSurrogate(truncated.last())) {
                truncated = truncated.dropLast(1)
            }
        }

        return truncated
    }

    /**
     * Whether the character at [index] is a surrogate that has no matching other half, and so
     * cannot be encoded to UTF-8.
     */
    private fun String.isUnpairedSurrogate(index: Int): Boolean {
        val character = this[index]

        return when {
            Character.isHighSurrogate(character) -> {
                index + 1 >= length || !Character.isLowSurrogate(this[index + 1])
            }
            Character.isLowSurrogate(character) -> {
                index == 0 || !Character.isHighSurrogate(this[index - 1])
            }
            else -> false
        }
    }

    private fun String.isReserved(): Boolean {
        return substringBefore('.').uppercase(Locale.ROOT) in RESERVED_NAMES
    }
}
