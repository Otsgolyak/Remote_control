package com.remote.control.data.adb

import android.content.Context
import com.remote.control.utils.Logger
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AdbCleanResult(
    val success: Boolean,
    val summary: String,
    val details: List<String>
)

object AdbCleaner {

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
     * Комплексная очистка ТВ от рекламы, телеметрии и мусора
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
                    // Проверяем, установлен ли пакет
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

                val summary = "Очистка завершена: AdGuard DNS активен, отключено служб: $disabledCount"
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
