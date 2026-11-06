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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * HTTPS用の鍵と自己署名証明書。初回に生成してアプリ専用領域に保存し、以降は同じものを使う
 * (証明書が変わらないので、ブラウザで警告を一度許可すれば同じ扱いになる)。
 * 端末のIPアドレスが証明書に含まれなくなった場合と、期限が近づいた場合は作り直す。
 * Apple の要件(SAN・serverAuth の EKU・有効期間825日以内)を満たす形で作る。
 * 証明書の生成に外部ライブラリを使わないよう、X.509 は最小限の DER を自前で組み立てる。
 */
object TlsIdentity {
    private const val KEY_FILE = "tls_key.der"
    private const val CERT_FILE = "tls_cert.der"
    private const val ALIAS = "server"
    // メモリ上のKeyStoreに入れるためだけのパスワード(空だと扱えない実装があるため固定値)
    private val PASSWORD = "uploadsrv".toCharArray()
    private const val COMMON_NAME = "UploadSrv"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val VALID_DAYS = 820L
    private const val RENEW_BEFORE_DAYS = 30L

    /**
     * [dir] に保存済みの鍵で、サーバー用の SSLContext を作る。
     * 保存が無い・期限が近い・[ipAddresses] のいずれかが証明書に含まれない場合は作り直す。
     */
    fun sslContext(dir: File, ipAddresses: List<String>): SSLContext {
        val now = Date()
        val (key, cert) = load(dir)?.takeIf { (_, c) -> isUsable(c, ipAddresses, now) }
            ?: create(ipAddresses, now).also { save(dir, it.first, it.second) }
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
        key to parseCertificate(certFile.readBytes())
    }.getOrNull()

    private fun isUsable(cert: X509Certificate, ipAddresses: List<String>, now: Date): Boolean {
        if (cert.notAfter.time - now.time < RENEW_BEFORE_DAYS * DAY_MS) return false
        val names = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
            .filter { it.size >= 2 && it[0] == SAN_IP }
            .map { it[1].toString() }
            .toSet()
        return names.containsAll(ipAddresses)
    }

    private fun save(dir: File, key: PrivateKey, cert: X509Certificate) {
        dir.mkdirs()
        File(dir, KEY_FILE).writeBytes(key.encoded)
        File(dir, CERT_FILE).writeBytes(cert.encoded)
    }

    /** EC(P-256)の鍵と、その鍵で署名した自己署名証明書([ipAddresses] と 127.0.0.1 を SAN に含む)を作る。 */
    internal fun create(ipAddresses: List<String>, now: Date = Date()): Pair<PrivateKey, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        val name = seq(set(seq(oid(OID_COMMON_NAME), utf8(COMMON_NAME))))
        // 接続側の時計が少し遅れていても「まだ有効でない」とならないよう1日前から有効にする
        val notBefore = Date(now.time - DAY_MS)
        val notAfter = Date(notBefore.time + VALID_DAYS * DAY_MS)
        // 正の整数にするため先頭ビットを落とす
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() }
        val signatureAlgorithm = seq(oid(OID_ECDSA_WITH_SHA256))
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // v3
            integer(BigInteger(serial)),
            signatureAlgorithm,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            keyPair.public.encoded, // SubjectPublicKeyInfo(DER)
            tlv(0xA3, extensions(ipAddresses)),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, signatureAlgorithm, tlv(0x03, byteArrayOf(0) + signature))
        return keyPair.private to parseCertificate(der)
    }

    private fun extensions(ipAddresses: List<String>): ByteArray {
        val ips = (ipAddresses + LOCALHOST_IP).distinct().mapNotNull(::ipv4Bytes)
        val san = seq(*ips.map { tlv(0x87, it) }.toTypedArray(), tlv(0x82, "localhost".toByteArray(Charsets.US_ASCII)))
        return seq(
            extension(OID_BASIC_CONSTRAINTS, critical = true, value = seq()),
            // digitalSignature のみ
            extension(OID_KEY_USAGE, critical = true, value = tlv(0x03, byteArrayOf(0x07, 0x80.toByte()))),
            extension(OID_EXT_KEY_USAGE, critical = false, value = seq(oid(OID_SERVER_AUTH))),
            extension(OID_SUBJECT_ALT_NAME, critical = false, value = san),
        )
    }

    private fun extension(id: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            seq(oid(id), tlv(0x01, byteArrayOf(0xFF.toByte())), tlv(0x04, value))
        } else {
            seq(oid(id), tlv(0x04, value))
        }

    /** "192.168.0.1" 形式を4バイトにする。IPv4でなければ null。 */
    private fun ipv4Bytes(ip: String): ByteArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
        return ByteArray(4) { values[it].toByte() }
    }

    private fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    // ---- 最小限の DER エンコーダ ----

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val SAN_IP = 7
    private const val LOCALHOST_IP = "127.0.0.1"

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

    private fun seq(vararg items: ByteArray) = tlv(0x30, concat(items))
    private fun set(vararg items: ByteArray) = tlv(0x31, concat(items))

    private fun concat(items: Array<out ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { items.forEach(::write) }.toByteArray()
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
        val year = Calendar.getInstance(UTC).apply { time = date }.get(Calendar.YEAR)
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
