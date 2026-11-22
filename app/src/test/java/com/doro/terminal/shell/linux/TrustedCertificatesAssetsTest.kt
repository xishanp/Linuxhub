package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class TrustedCertificatesAssetsTest {
    private val assets = File("src/main/assets/offline/certificates")

    @Test
    fun bundledCertificatesMatchVerifiedPackages() {
        assertEquals(
            mapOf(
                "openssl_3.0.13-0ubuntu3.16_arm64.deb" to
                    "07fa666e2dfe758e5c4e19ccc695a19e8a56fb512fa55bd7de89219a1e65d372",
                "ca-certificates_20260601~24.04.1_all.deb" to
                    "6bac2a01979e210d9eac1d4d56747ec709ea60654744d66705dc3c36e7629e50",
            ),
            assets.listFiles().orEmpty().associate { it.name to sha256(it) },
        )
    }

    @Test
    fun packagesArePresentAndNonEmpty() {
        assets.listFiles().orEmpty().forEach { file ->
            assertTrue("${file.name} 为空", file.length() > 0L)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
