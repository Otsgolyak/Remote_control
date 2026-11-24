package com.remote.control.data.apps

import android.content.Context
import com.remote.control.data.samsung.SamsungConnectionManager
import com.remote.control.utils.Logger
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class TvAppCandidate(
    val title: String,
    val packageNameOrAppId: String,
    val deepLink: String = "",
    val iconName: String = "tv",
    val isInstalledOnTv: Boolean = false
)

object TvAppCatalog {

    val POPULAR_APPS = listOf(
        TvAppCandidate("Кинопоиск", "ru.kinopoisk.tv", "https://hd.kinopoisk.ru", "movie"),
        TvAppCandidate("Иви", "ru.ivi.client", "https://www.ivi.ru", "movie"),
        TvAppCandidate("Okko", "ru.more.play", "https://okko.tv", "movie"),
        TvAppCandidate("Wink", "ru.rt.video.app.tv", "https://wink.ru", "video"),
        TvAppCandidate("VK Видео", "com.vk.video.tv", "https://vkvideo.ru", "video"),
        TvAppCandidate("Rutube", "ru.rutube.app.tv", "https://rutube.ru", "youtube"),
        TvAppCandidate("YouTube", "com.google.android.youtube.tv", "https://www.youtube.com", "youtube"),
        TvAppCandidate("Netflix", "com.netflix.ninja", "https://www.netflix.com/title", "movie"),
        TvAppCandidate("Prime Video", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com", "video"),
        TvAppCandidate("Media Station X", "de.benzac.msx", "msx.cc.ua", "tv"),
        TvAppCandidate("Lampa", "com.lampa.tv", "https://lampa.mx", "movie"),
        TvAppCandidate("NUM", "ru.yourok.num", "", "movie"),
        TvAppCandidate("VLC Media Player", "org.videolan.vlc", "", "video"),
        TvAppCandidate("KION", "ru.mts.kion", "https://kion.ru", "movie"),
        TvAppCandidate("START", "ru.start.androidbreak", "https://start.ru", "movie"),
        TvAppCandidate("Premier", "ru.premier", "https://premier.one", "movie"),
        TvAppCandidate("HDRezka", "com.rezka.client", "https://rezka.ag", "movie"),
        TvAppCandidate("LazyMedia Deluxe", "com.lazymedia.deluxe", "", "movie"),
        TvAppCandidate("SmartTube", "com.teamsmart.videomanager.tv", "", "youtube"),
        TvAppCandidate("TorrServe MatriX", "ru.yourok.torrserve", "", "settings"),
        TvAppCandidate("Браузер", "org.chromium.tv", "https://www.google.com", "web"),
        TvAppCandidate("Twitch", "tv.twitch.android.app", "https://www.twitch.tv", "video"),
        TvAppCandidate("Spotify", "com.spotify.tv.android", "https://open.spotify.com", "music")
    )

    private fun loadOrCreateKeyPair(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }

    suspend fun getAvailableTvApps(
        context: Context,
        deviceIp: String?,
        isSamsung: Boolean,
        samsungManager: SamsungConnectionManager?
    ): List<TvAppCandidate> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<TvAppCandidate>()

        if (isSamsung) {
            val samsungInstalled = samsungManager?.installedApps?.value.orEmpty()
            if (samsungInstalled.isNotEmpty()) {
                for (sApp in samsungInstalled) {
                    val icon = when {
                        sApp.appName.contains("tube", ignoreCase = true) -> "youtube"
                        sApp.appName.contains("film", ignoreCase = true) || sApp.appName.contains("movie", ignoreCase = true) || sApp.appName.contains("cinema", ignoreCase = true) -> "movie"
                        sApp.appName.contains("music", ignoreCase = true) || sApp.appName.contains("audio", ignoreCase = true) -> "music"
                        sApp.appName.contains("browser", ignoreCase = true) || sApp.appName.contains("internet", ignoreCase = true) -> "web"
                        else -> "tv"
                    }
                    candidates.add(
                        TvAppCandidate(
                            title = sApp.appName,
                            packageNameOrAppId = sApp.appId,
                            deepLink = sApp.appId,
                            iconName = icon,
                            isInstalledOnTv = true
                        )
                    )
                }
            } else {
                // Фоллбэк для Samsung
                val defaultSamsung = listOf(
                    TvAppCandidate("YouTube", "111299001912", "111299001912", "youtube", true),
                    TvAppCandidate("Netflix", "3201907018807", "3201907018807", "movie", true),
                    TvAppCandidate("Prime Video", "3201910019365", "3201910019365", "video", true),
                    TvAppCandidate("Браузер", "org.tizen.browser", "org.tizen.browser", "web", true),
                    TvAppCandidate("Кинопоиск", "3201612011406", "3201612011406", "movie", true),
                    TvAppCandidate("Иви", "11101200001", "11101200001", "movie", true),
                    TvAppCandidate("Okko", "111399000452", "111399000452", "movie", true),
                    TvAppCandidate("Wink", "3201806016390", "3201806016390", "video", true),
                    TvAppCandidate("Media Station X", "3201906018677", "3201906018677", "tv", true)
                )
                candidates.addAll(defaultSamsung)
            }
            return@withContext candidates
        }

        // Для Android TV / Google TV: пробуем запросить реальные пакеты через ADB если есть доступ
        val installedPackages = mutableSetOf<String>()
        if (!deviceIp.isNullOrBlank()) {
            try {
                val keyPair = loadOrCreateKeyPair(context)
                Dadb.create(deviceIp, 5555, keyPair).use { dadb ->
                    val pmOutput = dadb.shell("pm list packages -3").output
                    pmOutput.lines().forEach { line ->
                        val pkg = line.removePrefix("package:").trim()
                        if (pkg.isNotEmpty()) {
                            installedPackages.add(pkg)
                        }
                    }
                    Logger.log("TvAppCatalog: обнаружено ${installedPackages.size} сторонних пакетов на ТВ через ADB")
                }
            } catch (e: Exception) {
                Logger.log("TvAppCatalog: ADB недоступен для чтения пакетов (${e.message}), используется расширенный каталог")
            }
        }

        // Заполняем приложениями из проверенного каталога
        for (item in POPULAR_APPS) {
            val isInstalled = installedPackages.contains(item.packageNameOrAppId)
            candidates.add(item.copy(isInstalledOnTv = isInstalled))
        }

        // Сортируем: сначала реально установленные (если определили по ADB), затем популярные
        candidates.sortedWith(compareByDescending<TvAppCandidate> { it.isInstalledOnTv }.thenBy { it.title })
    }
}
