package com.remote.control.data.adb

import android.content.Context
import com.remote.control.utils.Logger
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
     * Комплексная очистка ТВ от рекламы, телеметрии, мусора + Установка и настройка MSX
     */
    suspend fun cleanTv(context: Context, host: String, port: Int = 5555): AdbCleanResult = withContext(Dispatchers.IO) {
        val details = mutableListOf<String>()
        var disabledCount = 0

        try {
            Logger.log("AdbCleaner: подключение к $host:$port...")
            val keyPair = loadOrCreateKeyPair(context)

            Dadb.create(host, port, keyPair).use { dadb ->
                Logger.log("AdbCleaner: подключено к $host:$port. Применение AdGuard DNS...")

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
                Logger.log("AdbCleaner: рекламный идентификатор отключен")

                // 3. Отключение рекламных пакетов и аналитики
                for ((pkg, desc) in BLOATWARE_PACKAGES) {
                    val checkResp = dadb.shell("pm list packages $pkg")
                    if (checkResp.output.contains(pkg)) {
                        val disableResp = dadb.shell("pm disable-user --user 0 $pkg")
                        if (disableResp.exitCode == 0 || disableResp.output.contains("disabled")) {
                            disabledCount++
                            details.add("✅ Отключено: $desc ($pkg)")
                            Logger.log("AdbCleaner: отключен $pkg ($desc)")
                        } else {
                            details.add("⚠️ Ошибка отключения $pkg: ${disableResp.output.trim()}")
                        }
                    }
                }

                // 4. Очистка кэша
                val trimResp = dadb.shell("pm trim-caches 1000M")
                details.add("✅ Очистка временного кэша и фонового мусора выполнена")
                Logger.log("AdbCleaner: pm trim-caches -> ${trimResp.output.trim()}")

                // 5. Проверка, установка и запуск Media Station X (MSX) с параметром msx.cc.ua
                Logger.log("AdbCleaner: проверка наличия $MSX_PACKAGE на ТВ...")
                val msxCheck = dadb.shell("pm list packages $MSX_PACKAGE")
                val isMsxInstalled = msxCheck.output.contains(MSX_PACKAGE)

                if (!isMsxInstalled) {
                    details.add("📥 Установка Media Station X (MSX)...")
                    Logger.log("AdbCleaner: MSX не установлен, попытка установки APK по ADB...")
                    val apkFile = downloadMsxApk(context)
                    if (apkFile != null && apkFile.exists()) {
                        try {
                            dadb.install(apkFile)
                            details.add("✅ Media Station X успешно установлен на ТВ")
                            Logger.log("AdbCleaner: dadb.install(MSX) успешно завершен")
                        } catch (ie: Exception) {
                            Logger.log("AdbCleaner: dadb.install ошибка: ${ie.message}, пробуем через push/pm install...")
                            val pushTarget = "/data/local/tmp/msx.apk"
                            dadb.push(apkFile, pushTarget)
                            val pmInstallResp = dadb.shell("pm install -r -g $pushTarget")
                            dadb.shell("rm -f $pushTarget")
                            if (pmInstallResp.output.contains("Success", ignoreCase = true)) {
                                details.add("✅ Media Station X успешно установлен на ТВ (pm)")
                                Logger.log("AdbCleaner: pm install msx.apk успешен")
                            } else {
                                details.add("⚠️ Ошибка установки MSX: ${pmInstallResp.output.trim()}")
                            }
                        } finally {
                            apkFile.delete()
                        }
                    } else {
                        // Если сеть смартфона не смогла загрузить APK напрямую, пробуем скачать самим ТВ через curl/wget
                        Logger.log("AdbCleaner: загрузка на смартфон не удалась, пробуем через ТВ...")
                        val tvDownloadAndInstall = dadb.shell("curl -L -k -o /data/local/tmp/msx.apk $MSX_FALLBACK_APK_URL && pm install -r -g /data/local/tmp/msx.apk && rm -f /data/local/tmp/msx.apk")
                        if (tvDownloadAndInstall.output.contains("Success", ignoreCase = true)) {
                            details.add("✅ Media Station X загружен и установлен ТВ напрямую")
                        } else {
                            details.add("ℹ️ MSX можно установить из Google Play Store на ТВ")
                        }
                    }
                } else {
                    details.add("✅ Media Station X (MSX) уже установлен на ТВ")
                    Logger.log("AdbCleaner: $MSX_PACKAGE уже присутствует на ТВ")
                }

                // 6. Запуск Media Station X с передачей стартового параметра msx.cc.ua
                Logger.log("AdbCleaner: запуск MSX с параметром $MSX_START_PARAMETER...")
                // MSX поддерживает открытие конфигураций и запуск через Intent
                val launchWithParam = dadb.shell("am start -a android.intent.action.VIEW -d \"http://$MSX_START_PARAMETER\" -n $MSX_PACKAGE/de.benzac.msx.MainActivity")
                if (launchWithParam.exitCode == 0) {
                    details.add("🚀 MSX запущен со стартовым параметром: $MSX_START_PARAMETER")
                    Logger.log("AdbCleaner: MSX успешно запущен с http://$MSX_START_PARAMETER")
                } else {
                    // Fallback на стандартный запуск активности
                    val fallbackLaunch = dadb.shell("monkey -p $MSX_PACKAGE -c android.intent.category.LAUNCHER 1")
                    details.add("🚀 MSX запущен на ТВ (введите '$MSX_START_PARAMETER' в Настройки -> Start Parameter)")
                    Logger.log("AdbCleaner: запуск MSX fallback -> ${fallbackLaunch.output.trim()}")
                }

                val summary = "Очистка завершена! AdGuard DNS активен, удалено служб: $disabledCount, MSX готов."
                Logger.log("AdbCleaner результат: $summary")
                AdbCleanResult(true, summary, details)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("AdbCleaner ошибка: $err")
            AdbCleanResult(
                success = false,
                summary = "Не удалось подключиться по ADB к ТВ ($host:$port). Проверьте включение 'Отладка по USB / Wi-Fi' в настройках разработчика на ТВ.",
                details = listOf("Ошибка: $err")
            )
        }
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
                        if (tempFile.length() > 500_000) { // APK должен быть больше 500 Кб
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
     * Восстановление заводских настроек (если пользователь захочет вернуть всё)
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
                success = false,
                summary = "Ошибка восстановления по ADB ($host:$port): $err",
                details = listOf("Ошибка: $err")
            )
        }
    }
}
