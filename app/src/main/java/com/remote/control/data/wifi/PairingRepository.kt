package com.remote.control.data.wifi

import com.google.protobuf.ByteString
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import pairing.PairingConfiguration
import pairing.PairingEncoding
import pairing.PairingMessage
import pairing.PairingOption
import pairing.PairingRequest
import pairing.PairingSecret
import pairing.RoleType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface PairingRepository {
    suspend fun startPairing(ip: String): Result<Unit>
    suspend fun confirmPin(pin: String): Result<Unit>
    fun getKeyPair(): KeyPair
    fun createSslContext(): SSLContext
}

class PairingRepositoryImpl : PairingRepository {
    private var keyStore: KeyStore? = null
    private var clientKeyPair: KeyPair? = null
    private var clientCert: X509Certificate? = null
    @Volatile
    private var lastServerCert: X509Certificate? = null

    private var pairingSocket: SSLSocket? = null
    private var pairingIn: InputStream? = null
    private var pairingOut: OutputStream? = null

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Synchronized
    private fun ensureKeyStore(): KeyStore {
        keyStore?.let { return it }

        val ksPassword = "android".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        val ctx = Logger.appContext
        val ksFile = ctx?.let { File(it.filesDir, "atv_client.p12") }

        if (ksFile != null && ksFile.exists()) {
            try {
                FileInputStream(ksFile).use { fis ->
                    ks.load(fis, ksPassword)
                }
                val key = ks.getKey("client", ksPassword) as? java.security.PrivateKey
                val cert = ks.getCertificate("client") as? X509Certificate
                if (key != null && cert != null) {
                    clientKeyPair = KeyPair(cert.publicKey, key)
                    clientCert = cert
                    keyStore = ks
                    Logger.log("Загружен сохраненный сертификат клиента mTLS")
                    return ks
                }
            } catch (e: Exception) {
                Logger.log("Пересоздание сертификата mTLS: ${e.message}")
            }
        }

        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val pair = keyGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val startDate = Date(now - 86400000L)
        val endDate = Date(now + 10L * 365 * 86400000L)
        val issuer = X500Name("CN=androidtvremote2, O=Android, C=US")
        val serial = BigInteger.valueOf(now)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            startDate,
            endDate,
            issuer,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(pair.private)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        ks.load(null, ksPassword)
        ks.setKeyEntry("client", pair.private, ksPassword, arrayOf(cert))

        if (ksFile != null) {
            runCatching {
                FileOutputStream(ksFile).use { fos ->
                    ks.store(fos, ksPassword)
                }
            }
        }

        clientKeyPair = pair
        clientCert = cert
        keyStore = ks
        Logger.log("Сгенерирован новый X.509 сертификат клиента mTLS")
        return ks
    }

    override fun getKeyPair(): KeyPair {
        ensureKeyStore()
        return clientKeyPair!!
    }

    override fun createSslContext(): SSLContext {
        val ks = ensureKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "android".toCharArray())

        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!chain.isNullOrEmpty()) {
                    lastServerCert = chain[0]
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        return sslContext
    }

    override suspend fun startPairing(ip: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            closePairingSocket()
            Logger.log("Начало сопряжения (Pairing) с $ip:6467...")

            val sslContext = createSslContext()
            val socket = sslContext.socketFactory.createSocket(ip, 6467) as SSLSocket
            socket.soTimeout = 30000
            socket.startHandshake()

            val peerCerts = runCatching { socket.session.peerCertificates }.getOrNull()
            if (!peerCerts.isNullOrEmpty() && peerCerts[0] is X509Certificate) {
                lastServerCert = peerCerts[0] as X509Certificate
            }

            val input = socket.inputStream
            val output = socket.outputStream
            pairingSocket = socket
            pairingIn = input
            pairingOut = output

            // 1. Send PairingRequest
            val reqMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(
                    PairingRequest.newBuilder()
                        .setServiceName("androidtvremote2")
                        .setClientName("Xiaomi TV Remote")
                        .build()
                )
                .build()
            reqMsg.writeDelimitedTo(output)
            output.flush()
            Logger.log("Отправлен PairingRequest на $ip:6467")

            val reqAck = PairingMessage.parseDelimitedFrom(input)
            Logger.log("Получен PairingRequestAck: status=${reqAck?.status}")

            // 2. Send PairingOption
            val encoding = PairingEncoding.newBuilder()
                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                .setSymbolLength(6)
                .build()

            val optMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingOption(
                    PairingOption.newBuilder()
                        .addInputEncodings(encoding)
                        .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                        .build()
                )
                .build()
            optMsg.writeDelimitedTo(output)
            output.flush()
            Logger.log("Отправлен PairingOption")

            val optResp = PairingMessage.parseDelimitedFrom(input)
            Logger.log("Получен ответ PairingOption: status=${optResp?.status}")

            // 3. Send PairingConfiguration
            val cfgMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingConfiguration(
                    PairingConfiguration.newBuilder()
                        .setEncoding(encoding)
                        .setClientRole(RoleType.ROLE_TYPE_INPUT)
                        .build()
                )
                .build()
            cfgMsg.writeDelimitedTo(output)
            output.flush()
            Logger.log("Отправлен PairingConfiguration")

            val cfgAck = PairingMessage.parseDelimitedFrom(input)
            Logger.log("Получен PairingConfigurationAck: status=${cfgAck?.status}. Ожидание ввода PIN-кода с экрана ТВ!")

            if (cfgAck == null || cfgAck.status != PairingMessage.Status.STATUS_OK) {
                throw IllegalStateException("ТВ отклонил запрос конфигурации: ${cfgAck?.status}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.log("Ошибка сопряжения с $ip:6467 -> ${e.javaClass.simpleName}: ${e.message}")
            closePairingSocket()
            Result.failure(e)
        }
    }

    override suspend fun confirmPin(pin: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanPin = pin.trim().uppercase()
            if (cleanPin.length != 6) {
                throw IllegalArgumentException("PIN-код должен состоять из 6 символов (HEX)")
            }

            val output = pairingOut ?: throw IllegalStateException("Сессия сопряжения не активна")
            val input = pairingIn ?: throw IllegalStateException("Сессия сопряжения не активна")
            val cCert = clientCert ?: throw IllegalStateException("Отсутствует сертификат клиента")
            val sCert = lastServerCert ?: throw IllegalStateException("Отсутствует сертификат ТВ")

            val clientPub = cCert.publicKey as RSAPublicKey
            val serverPub = sCert.publicKey as RSAPublicKey

            val pinBytes = hexToBytes(cleanPin)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(stripLeadingZero(clientPub.modulus.toByteArray()))
            digest.update(stripLeadingZero(clientPub.publicExponent.toByteArray()))
            digest.update(stripLeadingZero(serverPub.modulus.toByteArray()))
            digest.update(stripLeadingZero(serverPub.publicExponent.toByteArray()))
            digest.update(pinBytes.copyOfRange(1, pinBytes.size))
            val secretHash = digest.digest()

            val secretMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingSecret(
                    PairingSecret.newBuilder()
                        .setSecret(ByteString.copyFrom(secretHash))
                        .build()
                )
                .build()
            secretMsg.writeDelimitedTo(output)
            output.flush()
            Logger.log("Отправлен PairingSecret для PIN=$cleanPin")

            val secretAck = PairingMessage.parseDelimitedFrom(input)
            Logger.log("Получен PairingSecretAck: status=${secretAck?.status}")

            closePairingSocket()

            if (secretAck == null || secretAck.status != PairingMessage.Status.STATUS_OK) {
                throw IllegalStateException("Неверный PIN-код или отказ ТВ (${secretAck?.status})")
            }

            Logger.log("Сопряжение с ТВ успешно завершено!")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.log("Ошибка подтверждения PIN: ${e.javaClass.simpleName}: ${e.message}")
            closePairingSocket()
            Result.failure(e)
        }
    }

    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val idx = i * 2
            result[i] = hex.substring(idx, idx + 2).toInt(16).toByte()
        }
        return result
    }

    private fun closePairingSocket() {
        runCatching { pairingSocket?.close() }
        pairingSocket = null
        pairingIn = null
        pairingOut = null
    }
}
