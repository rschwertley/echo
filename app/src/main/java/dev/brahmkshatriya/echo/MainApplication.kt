package dev.brahmkshatriya.echo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import dev.brahmkshatriya.echo.di.DI
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.utils.AppShortcuts.configureAppShortcuts
import dev.brahmkshatriya.echo.utils.CoroutineUtils
import dev.brahmkshatriya.echo.utils.CrashKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.androix.startup.KoinStartup
import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.koinConfiguration
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(KoinExperimentalAPI::class)
class MainApplication : Application(), KoinStartup, SingletonImageLoader.Factory {

    // Single source of truth for Koin setup — referenced by BOTH the App-Startup path (onKoinStartup) and
    // the deferred ensureKoin() fallback, so the two configs can never drift.
    private fun KoinApplication.applyKoinModules() {
        androidContext(this@MainApplication)
        modules(DI.appModule)
        workManagerFactory()
    }

    override fun onKoinStartup() = koinConfiguration { applyKoinModules() }

    private val settings by inject<SharedPreferences>()
    private val extensionLoader by inject<ExtensionLoader>()

    // Guards the deferred init to exactly-once across the (mutually exclusive) unlocked onCreate path and
    // the ACTION_USER_UNLOCKED receiver — belt-and-suspenders against any delivery/TOCTOU race.
    private val initDone = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // First thing on process birth: record the monotonic start time for the process_age_s crash key.
        // No DI / settings / Firebase touched here, so it's safe even on a pre-unlock Direct-Boot spawn.
        CrashKeys.markProcessStart()
        // Static for the process and needed on EVERY report, so record it here rather than recomputing.
        // Its absence cost a full triage round: a Pixel 10 report could not be tied to a Play install
        // without it, and a wrong theory about how the binary was installed took a round to disprove.
        CrashKeys.recordInstallSource(this)
        CoroutineUtils.setDebug()
        // Firebase's directBootAware providers can spawn this process PRE-UNLOCK, where the (non-
        // directBootAware) androidx.startup InitializationProvider is skipped — so Koin isn't started AND
        // credential-encrypted storage (settings) isn't readable. Defer all settings/DI-dependent init
        // until the user unlocks; the normal (unlocked) launch runs it inline exactly as before.
        if (isUserUnlocked()) initAfterUnlock()
        else registerUnlockReceiver()
    }

    // UserManager is a core system service (effectively never null); default to "unlocked" so a null
    // service can't wedge the app into permanent deferral. isUserUnlocked is API 24+ (minSdk 24).
    private fun isUserUnlocked() =
        getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    // On a pre-unlock spawn the InitializationProvider was skipped and App Startup does NOT re-run at
    // unlock, so Koin can still be down when we reach the deferred init — start it (idempotently, with the
    // shared declaration) before resolving any inject. No-op on the normal path (App Startup already ran).
    private fun ensureKoin() {
        if (GlobalContext.getOrNull() == null) startKoin { applyKoinModules() }
    }

    // The real onCreate work, run either inline (unlocked launch) or once at ACTION_USER_UNLOCKED. Order is
    // load-bearing: Koin first, THEN the settings inject / CE read, THEN shortcuts.
    private fun initAfterUnlock() {
        if (!initDone.compareAndSet(false, true)) return
        ensureKoin()
        applyLocale(settings)
        CoroutineScope(Dispatchers.IO).launch { configureAppShortcuts(extensionLoader) }
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                runCatching { unregisterReceiver(this) }
                initAfterUnlock()
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ACTION_USER_UNLOCKED is a protected system broadcast (only the OS sends it), so NOT_EXPORTED
            // is correct and safer — mirrors PlayerService.clearQueueReceiver.
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        // TOCTOU: if the device unlocked between the isUserUnlocked() check in onCreate and this
        // registration, the broadcast may already be gone — re-check and run inline. initDone's CAS makes
        // this safe if the receiver also fires.
        if (isUserUnlocked()) {
            runCatching { unregisterReceiver(receiver) }
            initAfterUnlock()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // 0.15 (≈38MB of a 256MB heap) instead of 0.25 (≈64MB): decoded covers are SOFTWARE
                    // bitmaps on the Java heap (allowHardware(false), needed by the blur/crop transforms), so
                    // this cache counts against the heap cap. 0.15 still holds ~75–125 downsampled covers
                    // (~4–8 screens), so normal browsing never re-decodes; only very deep scroll-back re-decodes
                    // from the disk cache (fast, no network, no correctness change).
                    .maxSizePercent(context, 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image-cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100MB
                    .build()
            }
            .allowHardware(false)
            .crossfade(true)
            .build()
    }

    override fun getPackageName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.equals(CLASS_NAME, ignoreCase = true)
                        && FUNCTION_SET.any { trace.methodName.equals(it, ignoreCase = true) }
            }
            if (isChromiumCall) return spoofedPackageName(applicationContext)
        }
        return super.getPackageName()
    }

    private fun spoofedPackageName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(CHROME_PACKAGE, PackageManager.GET_META_DATA)
            CHROME_PACKAGE
        }.getOrElse {
            SYSTEM_SETTINGS_PACKAGE
        }
    }

    companion object {
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
        private const val CLASS_NAME = "org.chromium.base.BuildInfo"
        private val FUNCTION_SET = setOf("getAll", "getPackageName", "<init>")

        fun getCurrentLanguage(sharedPref: SharedPreferences) =
            sharedPref.getString("language", null) ?: "system"

        fun setCurrentLanguage(sharedPref: SharedPreferences, locale: String?) {
            sharedPref.edit { putString("language", locale) }
            applyLocale(sharedPref)
        }

        fun applyLocale(sharedPref: SharedPreferences) {
            val value = sharedPref.getString("language", null) ?: "system"
            val locale = if (value == "system") LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(value)
            AppCompatDelegate.setApplicationLocales(locale)
        }

        val languages = mapOf(
            "ar" to "العربية",
            "as" to "Assamese",
            "be" to "Беларуская",
            "bn" to "বাংলা",
            "ca" to "Català",
            "de" to "Deutsch",
            "es" to "Español",
            "fa" to "فارسی",
            "fr" to "Français",
            "en" to "English",
            "hi" to "हिन्दी",
            "hng" to "Hinglish",
            "hu" to "Magyar",
            "in" to "Bahasa Indonesia",
            "it" to "Italiano",
            "iw" to "עברית",
            "ja" to "日本語",
            "ko" to "한국어",
            "lv" to "Latviski",
            "ms" to "Bahasa Melayu",
            "pl" to "Polski",
            "pt" to "Português",
            "pt-rBR" to "Português (Brasil)",
            "ru" to "Русский",
            "sa" to "संस्कृतम्",
            "si" to "සිංහල",
            "sk" to "Slovenčina",
            "sr" to "Српски",
            "ta" to "தமிழ்",
            "th" to "ไทย",
            "tl" to "Filipino",
            "tr" to "Türkçe",
            "uk" to "Українська",
            "vi" to "Tiếng Việt",
            "zh-rCN" to "中文 (简体)",
            "zh-rTW" to "中文 (繁體)"
        )
    }
}