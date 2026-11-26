package com.remote.control.data.adb

import android.content.Context
import android.net.Uri
import com.remote.control.utils.Logger
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AdbCleanResult(
    val success: Boolean,
    val summary: String,
    val details: List<String>
)

object AdbCleaner {

    private const val MSX_PACKAGE = "de.benzac.msx"
    private const val MSX_START_PARAMETER = "msx.cc.ua"
    private const val MSX_APK_DOWNLOAD_URL = "https://raw.githubusercontent.com/Otsgolyak/Remote_control/main/tools/msx.apk"
    // Резервный проверенный источник MSX APK
    private const val MSX_FALLBACK_APK_URL = "https://msx.benzac.de/apk/msx.apk"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun loadOrCreateKeyPair(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            Logger.log("AdbCleaner: ключи не найдены, генерация новой пары ADB-ключей...")
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
            Logger.log("AdbCleaner: новая пара ключей успешно сгенерирована")
        }
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }

    /**
     * Список системных рекламных служб, трекеров и аналитики Xiaomi и Android TV
     */
    private val BLOATWARE_PACKAGES = listOf(
        // Xiaomi PatchWall / MiTV реклама и аналитика
        "com.xiaomi.mitv.advertise" to "Служба рекламы Xiaomi TV",
        "com.xiaomi.mitv.analytics" to "Сбор телеметрии Xiaomi TV",
        "com.miui.analytics" to "Аналитика MIUI TV",
        "com.xiaomi.mitv.upgrade" to "Рекламный апдейтер Xiaomi",
        "com.xiaomi.mitv.payment" to "Платежи и магазин Xiaomi",
        "com.xiaomi.mitv.shop" to "Рекламный каталог Xiaomi",
        "com.xiaomi.mitv.tvpush" to "Push-уведомления и реклама",
        "com.xiaomi.devicelist" to "Сбор данных об устройствах Xiaomi",
        
        // Google TV / Android TV рекламные рекомендации
        "com.google.android.tvrecommendations" to "Рекламные каналы/рекомендации Google TV",
        "com.android.providers.partnerbookmarks" to "Партнерские закладки и промо",
        "com.google.android.partnersetup" to "Партнерская телеметрия Google"
    )

    /**
     * 1. Очистка системного кэша и временных файлов
     */
    suspend fun cleanCache(context: Context, host: String, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        try {
            Logger.log("AdbCleaner: очистка кэша на $host:$port...")
            val keyPair = loadOrCreateKeyPair(context)
            Dadb.create(host, port, keyPair).use { dadb ->
                val trimResp = dadb.shell("pm trim-caches 1000M")
                details.add("✅ Системный кэш приложений очищен (pm trim-caches)")
                Logger.log("AdbCleaner: pm trim-caches -> ${trimResp.output.trim()}")

                dadb.shell("rm -rf /data/local/tmp/*.apk /data/local/tmp/*.tmp")
                details.add("✅ Каталог временных файлов /data/local/tmp очищен")

                val summary = "Очистка кэша завершена! Кэш приложений и временные файлы освобождены."
                Logger.log("AdbCleaner: $summary")
                AdbCleanResult(true, summary, details)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("AdbCleaner cleanCache ошибка: $err")
            AdbCleanResult(
                false,
                "Не удалось подключиться по ADB к ТВ ($host:$port). Проверьте 'Отладка по USB / Wi-Fi' на ТВ.",
                listOf("Ошибка: $err")
            )
        }
    }

    /**
     * 2. Блокировка рекламы, отключение рекламных трекеров и AdGuard DNS
     */
    suspend fun blockAds(context: Context, host: String, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        var disabledCount = 0
        try {
            Logger.log("AdbCleaner: блокировка рекламы на $host:$port...")
            val keyPair = loadOrCreateKeyPair(context)
            Dadb.create(host, port, keyPair).use { dadb ->
                // 1. Установка AdGuard DNS over TLS (блокирует рекламу на уровне всей системы)
                val dnsModeResp = dadb.shell("settings put global private_dns_mode hostname")
                val dnsHostResp = dadb.shell("settings put global private_dns_specifier dns.adguard-dns.com")
                if (dnsModeResp.exitCode == 0 && dnsHostResp.exitCode == 0) {
                    details.add("✅ AdGuard DNS активирован (dns.adguard-dns.com)")
                    Logger.log("AdbCleaner: AdGuard DNS успешно включен")
                } else {
                    details.add("⚠️ AdGuard DNS: не удалось установить приватный DNS")
                }

                // 2. Отключение рекламного идентификатора и трекинга
                dadb.shell("settings put secure limit_ad_tracking 1")
                dadb.shell("settings put secure advertising_id_opt_out 1")
                dadb.shell("settings put global usage_reporting_enabled 0")
                details.add("✅ Ограничение рекламного трекинга и телеметрии включено")

                // 3. Отключение рекламных пакетов и аналитики
                for ((pkg, desc) in BLOATWARE_PACKAGES) {
                    val checkResp = dadb.shell("pm list packages $pkg")
                    if (checkResp.output.contains(pkg)) {
                        val disableResp = dadb.shell("pm disable-user --user 0 $pkg")
                        if (disableResp.exitCode == 0 || disableResp.output.contains("disabled")) {
                            disabledCount++
                            details.add("✅ Отключено: $desc ($pkg)")
                        } else {
                            details.add("⚠️ Ошибка отключения $pkg: ${disableResp.output.trim()}")
                        }
                    }
                }

                val summary = "Блокировка рекламы завершена! AdGuard DNS активен, отключено служб: $disabledCount."
                Logger.log("AdbCleaner blockAds: $summary")
                AdbCleanResult(true, summary, details)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("AdbCleaner blockAds ошибка: $err")
            AdbCleanResult(
                false,
                "Ошибка блокировки рекламы по ADB ($host:$port): $err",
                listOf("Ошибка: $err")
            )
        }
    }

    /**
     * 3. Установка Media Station X (MSX) с настройкой портала msx.cc.ua
     */
    suspend fun installMsx(context: Context, host: String, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        try {
            Logger.log("AdbCleaner: установка MSX на $host:$port...")
            val keyPair = loadOrCreateKeyPair(context)
            Dadb.create(host, port, keyPair).use { dadb ->
                val msxCheck = dadb.shell("pm list packages $MSX_PACKAGE")
                val isMsxInstalled = msxCheck.output.contains(MSX_PACKAGE)

                if (!isMsxInstalled) {
                    details.add("📥 Скачивание и установка Media Station X...")
                    val apkFile = downloadMsxApk(context)
                    if (apkFile != null && apkFile.exists()) {
                        try {
                            dadb.install(apkFile)
                            details.add("✅ Media Station X успешно установлен на ТВ")
                        } catch (ie: Exception) {
                            val pushTarget = "/data/local/tmp/msx.apk"
                            dadb.push(apkFile, pushTarget)
                            val pmInstallResp = dadb.shell("pm install -r -g $pushTarget")
                            dadb.shell("rm -f $pushTarget")
                            if (pmInstallResp.output.contains("Success", ignoreCase = true)) {
                                details.add("✅ Media Station X успешно установлен на ТВ (pm)")
                            } else {
                                details.add("⚠️ Ошибка установки MSX: ${pmInstallResp.output.trim()}")
                            }
                        } finally {
                            apkFile.delete()
                        }
                    } else {
                        val tvDownloadAndInstall = dadb.shell("curl -L -k -o /data/local/tmp/msx.apk $MSX_FALLBACK_APK_URL && pm install -r -g /data/local/tmp/msx.apk && rm -f /data/local/tmp/msx.apk")
                        if (tvDownloadAndInstall.output.contains("Success", ignoreCase = true)) {
                            details.add("✅ Media Station X загружен и установлен ТВ напрямую")
                        } else {
                            details.add("ℹ️ MSX можно установить из Google Play Store на ТВ")
                        }
                    }
                } else {
                    details.add("✅ Media Station X (MSX) уже установлен на ТВ")
                }

                // Запуск с параметром msx.cc.ua
                val deepLinkCmd = "am start -a android.intent.action.VIEW -d \"msx://de.benzac.msx?start=$MSX_START_PARAMETER\""
                val explicitCmd = "am start -a android.intent.action.VIEW -d \"http://$MSX_START_PARAMETER\" -n $MSX_PACKAGE/de.benzac.msx.MainActivity"
                val launchResult = dadb.shell(deepLinkCmd)
                if (launchResult.exitCode != 0) {
                    dadb.shell(explicitCmd)
                }
                dadb.shell("monkey -p $MSX_PACKAGE -c android.intent.category.LAUNCHER 1")
                details.add("🚀 Media Station X запущен с каталогом: $MSX_START_PARAMETER")

                val summary = "Media Station X готов к использованию на ТВ!"
                AdbCleanResult(true, summary, details)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            AdbCleanResult(false, "Ошибка установки Media Station X ($host:$port): $err", listOf("Ошибка: $err"))
        }
    }

    /**
     * 4. Установка произвольного APK файла со смартфона на ТВ по ADB
     */
    suspend fun installCustomApk(context: Context, host: String, apkUri: Uri, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        var tempFile: File? = null
        try {
            Logger.log("AdbCleaner: подготовка пользовательского APK к установке на $host:$port...")
            details.add("📂 Чтение выбранного файла APK...")
            val keyPair = loadOrCreateKeyPair(context)

            tempFile = File(context.cacheDir, "custom_app_${System.currentTimeMillis()}.apk")
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Не удалось прочитать выбранный файл")

            val sizeMb = "%.1f".format(tempFile.length() / (1024.0 * 1024.0))
            details.add("📦 Размер файла: $sizeMb МБ")
            Logger.log("AdbCleaner: размер APK $sizeMb МБ, передача на ТВ...")

            Dadb.create(host, port, keyPair).use { dadb ->
                val pushTarget = "/data/local/tmp/custom_install.apk"
                details.add("📡 Передача APK на телевизор...")
                dadb.push(tempFile, pushTarget)
                details.add("⚙️ Установка приложения (pm install)...")
                val installResp = dadb.shell("pm install -r -g $pushTarget")
                dadb.shell("rm -f $pushTarget")

                if (installResp.output.contains("Success", ignoreCase = true)) {
                    details.add("✅ Приложение успешно установлено на телевизор!")
                    AdbCleanResult(true, "APK успешно установлен на ТВ!", details)
                } else {
                    details.add("⚠️ Ошибка pm install: ${installResp.output.trim()}")
                    AdbCleanResult(false, "Ошибка при установке: ${installResp.output.trim()}", details)
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("AdbCleaner installCustomApk ошибка: $err")
            AdbCleanResult(false, "Не удалось установить APK: $err", listOf("Ошибка: $err"))
        } finally {
            tempFile?.delete()
        }
    }

    /**
     * Комплексная очистка ТВ от рекламы, телеметрии, мусора + Установка и настройка MSX
     */
    suspend fun cleanTv(context: Context, host: String, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        val blockRes = blockAds(context, host, port)
        details.addAll(blockRes.details)
        if (!blockRes.success) return@withContext blockRes

        val cacheRes = cleanCache(context, host, port)
        details.addAll(cacheRes.details)

        val msxRes = installMsx(context, host, port)
        details.addAll(msxRes.details)

        AdbCleanResult(
            true,
            "Комплексная очистка завершена: реклама заблокирована, кэш очищен, MSX готов!",
            details
        )
    }

    private fun downloadMsxApk(context: Context): File? {
        val urls = listOf(
            "https://msx.benzac.de/apk/msx.apk",
            "https://github.com/Otsgolyak/Remote_control/releases/download/v1.0.0/msx.apk",
            "https://d.apkpure.com/b/APK/de.benzac.msx?version=latest"
        )
        for (url in urls) {
            try {
                Logger.log("AdbCleaner: скачивание MSX APK из $url...")
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val tempFile = File(context.cacheDir, "msx_install.apk")
                        tempFile.outputStream().use { out ->
                            response.body!!.byteStream().copyTo(out)
                        }
                        if (tempFile.length() > 500_000) {
                            Logger.log("AdbCleaner: MSX APK успешно скачан (${tempFile.length()} байт)")
                            return tempFile
                        } else {
                            tempFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log("AdbCleaner: ошибка загрузки MSX с $url: ${e.message}")
            }
        }
        return null
    }

    /**
     * Восстановление заводских настроек (сброс DNS и включение служб обратно)
     */
    suspend fun restoreTv(context: Context, host: String, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        var enabledCount = 0

        try {
            val keyPair = loadOrCreateKeyPair(context)
            Dadb.create(host, port, keyPair).use { dadb ->
                // 1. Сброс Private DNS на авто
                dadb.shell("settings put global private_dns_mode opportunistic")
                dadb.shell("settings delete global private_dns_specifier")
                details.add("✅ Приватный DNS сброшен на стандартный")

                // 2. Включение служб обратно
                for ((pkg, desc) in BLOATWARE_PACKAGES) {
                    val checkResp = dadb.shell("pm list packages $pkg")
                    if (checkResp.output.contains(pkg)) {
                        val enableResp = dadb.shell("pm enable $pkg")
                        if (enableResp.exitCode == 0 || enableResp.output.contains("enabled")) {
                            enabledCount++
                            details.add("✅ Восстановлено: $desc ($pkg)")
                        }
                    }
                }

                val summary = "Восстановление завершено: служб включено: $enabledCount, DNS сброшен"
                Logger.log("AdbCleaner restore: $summary")
                AdbCleanResult(true, summary, details)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("AdbCleaner restore ошибка: $err")
            AdbCleanResult(
                false,
                "Ошибка восстановления по ADB ($host:$port): $err",
                listOf("Ошибка: $err")
            )
        }
    }
}
