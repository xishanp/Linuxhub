package com.doro.terminal.shell.desktop

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Local-only TLS for the LAN browser gateway.
 *
 * Chrome/Edge expose WebCodecs only in a secure context. A LAN browser reaches
 * the phone over plain HTTP, which is not a trustworthy origin, so WebCodecs is
 * unavailable there even though the browser is Chrome/Edge. This generates and
 * reuses a self-signed certificate for the current LAN address so the browser
 * can use HTTPS after the one-time "continue to site" warning.
 */
internal object LocalTls {
    private const val STORE_FILE = "remote-tls.p12"
    private const val HOST_FILE = "remote-tls.host"
    private val password = "linuxhub".toCharArray()

    fun serverContext(context: Context, host: String?): SSLContext {
        val storeFile = File(context.filesDir, STORE_FILE)
        val hostFile = File(context.filesDir, HOST_FILE)
        val desiredHost = host?.trim().orEmpty()
        val canReuse = storeFile.isFile && hostFile.isFile &&
            hostFile.readText().trim() == desiredHost
        val keyStore = if (canReuse) {
            KeyStore.getInstance("PKCS12").apply {
                FileInputStream(storeFile).use { load(it, password) }
            }
        } else {
            createKeyStore(desiredHost).also { generated ->
                FileOutputStream(storeFile).use { generated.store(it, password) }
                hostFile.writeText(desiredHost)
            }
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm(),
        ).apply {
            init(keyStore, password)
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
    }

    private fun createKeyStore(host: String): KeyStore {
        val provider = BouncyCastleProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=LinuxHub Local")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs(),
            Date(now - 24L * 60 * 60 * 1000),
            Date(now + 3650L * 24 * 60 * 60 * 1000),
            subject,
            keyPair.public,
        )
        val names = buildList {
            add(GeneralName(GeneralName.dNSName, "localhost"))
            add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
            if (host.isNotBlank() && host != "127.0.0.1" && host != "0.0.0.0") {
                add(GeneralName(GeneralName.iPAddress, host))
            }
        }
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(names.toTypedArray()),
        )
        builder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false),
        )
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))
        certificate.checkValidity()
        return KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("linuxhub", keyPair.private, password, arrayOf(certificate))
        }
    }
}
