package com.toshi0907.uploadsrv

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * HTTPS用の鍵と自己署名証明書。初回に生成してアプリ専用領域に保存し、以降は同じものを使う
 * (証明書が変わらないので、ブラウザで警告を一度許可すれば同じ扱いになる)。
 * 証明書の生成に外部ライブラリを使わないよう、X.509 は最小限の DER を自前で組み立てる。
 */
object TlsIdentity {
    private const val KEY_FILE = "tls_key.der"
    private const val CERT_FILE = "tls_cert.der"
    private const val ALIAS = "server"
    // メモリ上のKeyStoreに入れるためだけのパスワード(空だと扱えない実装があるため固定値)
    private val PASSWORD = "uploadsrv".toCharArray()
    private const val COMMON_NAME = "UploadSrv"
    private const val VALID_YEARS = 10

    /** [dir] に保存済みの鍵(無ければ生成)で、サーバー用の SSLContext を作る。 */
    fun sslContext(dir: File): SSLContext {
        val (key, cert) = load(dir) ?: create().also { save(dir, it.first, it.second) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(ALIAS, key, PASSWORD, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, PASSWORD)
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun load(dir: File): Pair<PrivateKey, X509Certificate>? = runCatching {
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        if (!keyFile.isFile || !certFile.isFile) return null
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = parseCertificate(certFile.readBytes())
        // 期限切れなら作り直す
        cert.checkValidity()
        key to cert
    }.getOrNull()

    private fun save(dir: File, key: PrivateKey, cert: X509Certificate) {
        dir.mkdirs()
        File(dir, KEY_FILE).writeBytes(key.encoded)
        File(dir, CERT_FILE).writeBytes(cert.encoded)
    }

    /** EC(P-256)の鍵と、その鍵で署名した自己署名証明書を作る。 */
    internal fun create(now: Date = Date()): Pair<PrivateKey, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        val name = seq(set(seq(oid(OID_COMMON_NAME), utf8(COMMON_NAME))))
        val notAfter = Date(now.time + VALID_YEARS * 365L * 24 * 60 * 60 * 1000)
        // 正の整数にするため先頭ビットを落とす
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() }
        val signatureAlgorithm = seq(oid(OID_ECDSA_WITH_SHA256))
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // v3
            integer(BigInteger(serial)),
            signatureAlgorithm,
            name,
            seq(time(now), time(notAfter)),
            name,
            keyPair.public.encoded, // SubjectPublicKeyInfo(DER)
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, signatureAlgorithm, tlv(0x03, byteArrayOf(0) + signature))
        return keyPair.private to parseCertificate(der)
    }

    private fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    // ---- 最小限の DER エンコーダ ----

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            else -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun seq(vararg items: ByteArray) = tlv(0x30, items.fold(ByteArray(0)) { acc, b -> acc + b })
    private fun set(vararg items: ByteArray) = tlv(0x31, items.fold(ByteArray(0)) { acc, b -> acc + b })
    private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    private fun utf8(value: String) = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map(String::toLong)
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val bytes = mutableListOf<Int>()
            var v = part
            do {
                bytes.add(0, (v and 0x7F).toInt())
                v = v shr 7
            } while (v > 0)
            bytes.forEachIndexed { i, b -> out.write(if (i < bytes.size - 1) b or 0x80 else b) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** RFC 5280: 2049年までは UTCTime、2050年以降は GeneralizedTime。 */
    private fun time(date: Date): ByteArray {
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = UTC }.format(date).toInt()
        return if (year < 2050) {
            tlv(0x17, format("yyMMddHHmmss'Z'", date))
        } else {
            tlv(0x18, format("yyyyMMddHHmmss'Z'", date))
        }
    }

    private fun format(pattern: String, date: Date): ByteArray =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(date).toByteArray(Charsets.US_ASCII)

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
}
