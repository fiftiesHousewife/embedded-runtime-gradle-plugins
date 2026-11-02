package io.github.fiftieshousewife.appcore

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

internal object Sha256 {
    /** A file's SHA-256 in lower-case hex, read as a stream: a JDK's jmods run to tens of MB. */
    fun of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(digest.digest())
    }
}
