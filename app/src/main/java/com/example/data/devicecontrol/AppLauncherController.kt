package com.example.data.devicecontrol

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

class AppLauncherController(private val context: Context) : DeviceControlCapability {

    override val capabilityId: String = "system.app_launcher"

    private val appContext = context.applicationContext

    override fun isSupported(): Boolean = true

    fun getSupportedApps(): List<SupportedAppInfo> {
        return SupportedAppId.entries.map { appId ->
            val resolved = resolveLaunchIntentFor(appId) != null
            SupportedAppInfo(appId = appId, isAvailable = resolved)
        }
    }

    /**
     * Detects and lists all launchable installed apps visible via Android 11+ <queries>
     * (ACTION_MAIN + CATEGORY_LAUNCHER) without requiring QUERY_ALL_PACKAGES.
     */
    fun getAllLaunchableApps(): List<InstalledAppInfo> {
        val pm = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolvedList = queryActivitiesCompat(pm, launcherIntent, 0)
        val seenPackages = HashSet<String>()
        val results = mutableListOf<InstalledAppInfo>()

        for (info in resolvedList) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == appContext.packageName || !activityInfo.exported) continue
            if (!seenPackages.add(pkg)) continue

            val label = runCatching {
                info.loadLabel(pm)?.toString()?.trim()
            }.getOrNull().orEmpty().ifBlank { pkg }

            val appFlags = activityInfo.applicationInfo?.flags ?: 0
            val isSystem = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0

            results.add(
                InstalledAppInfo(
                    appName = label,
                    packageName = pkg,
                    activityName = activityInfo.name ?: "",
                    isSystemApp = isSystem
                )
            )
        }

        return results.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }

    fun launchApp(appId: SupportedAppId): DeviceActionResult {
        val launchIntent = resolveLaunchIntentFor(appId)
        if (launchIntent == null) {
            Log.w(TAG, "No launchable Activity resolved for $appId")
            return DeviceActionResult.Unavailable(
                messageEn = "No compatible ${appId.displayEn()} app is installed on this device.",
                messageBn = "এই ডিভাইসে উপযুক্ত ${appId.displayBn()} অ্যাপ পাওয়া যায়নি।"
            )
        }

        return startActivitySafely(
            intent = launchIntent,
            labelEn = appId.displayEn(),
            labelBn = appId.displayBn(),
            actionTag = "APP:${appId.tagValue}"
        )
    }

    fun launchByPackageName(packageName: String, displayName: String? = null): DeviceActionResult {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) {
            return DeviceActionResult.Unavailable(
                messageEn = "No package name provided.",
                messageBn = "কোনো প্যাকেজের নাম দেওয়া হয়নি।"
            )
        }

        val pm = appContext.packageManager
        val directLaunch = pm.getLaunchIntentForPackage(cleanPkg)
        val resolvedLaunch = directLaunch ?: run {
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(cleanPkg)
            }
            val match = queryActivitiesCompat(pm, queryIntent, 0)
                .firstOrNull { isUsableResolveInfo(it) }
            match?.activityInfo?.let { info ->
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(info.packageName, info.name)
                }
            }
        }

        val label = displayName?.takeIf { it.isNotBlank() } ?: runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(cleanPkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(cleanPkg, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(cleanPkg)

        if (resolvedLaunch == null) {
            return DeviceActionResult.Unavailable(
                messageEn = "$label ($cleanPkg) is not installed or has no launchable screen.",
                messageBn = "$label ($cleanPkg) ইনস্টল করা নেই বা খোলা সম্ভব নয়।"
            )
        }

        return startActivitySafely(
            intent = resolvedLaunch,
            labelEn = label,
            labelBn = label,
            actionTag = "PKG:$cleanPkg"
        )
    }

    fun launchByNameOrQuery(
        query: String,
        cachedApps: List<InstalledAppInfo>? = null
    ): DeviceActionResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return DeviceActionResult.Unavailable(
                messageEn = "Please tell me which app you want to open.",
                messageBn = "কোন অ্যাপটি খুলতে চান তা অনুগ্রহ করে বলুন।"
            )
        }

        // If the query looks like a package name (e.g. com.google.android.apps.maps), try direct package launch first
        if (trimmed.contains('.') && !trimmed.contains(' ')) {
            val pkgResult = launchByPackageName(trimmed)
            if (pkgResult is DeviceActionResult.Success) return pkgResult
        }

        // Scan installed apps first for accurate fuzzy matching by app name or Bengali alias
        val installed = cachedApps ?: getAllLaunchableApps()
        val bestMatch = FuzzyAppMatcher.findBestMatch(trimmed, installed)
        if (bestMatch != null && bestMatch.score >= 0.85f) {
            return launchByPackageName(bestMatch.app.packageName, bestMatch.app.appName)
        }

        // Check built-in system intent shortcuts (Maps, Camera, Calculator, Settings, Clock, etc.)
        val shortcut = FuzzyAppMatcher.matchSupportedShortcut(trimmed)
        if (shortcut != null) {
            val shortcutResult = launchApp(shortcut)
            if (shortcutResult is DeviceActionResult.Success) return shortcutResult
        }

        // Fall back to fuzzy match with standard threshold (>= 0.62f)
        if (bestMatch != null) {
            return launchByPackageName(bestMatch.app.packageName, bestMatch.app.appName)
        }

        return DeviceActionResult.Unavailable(
            messageEn = "Could not find an installed app matching \"$trimmed\".",
            messageBn = "\"$trimmed\" নামের কোনো ইনস্টল করা অ্যাপ পাওয়া যায়নি।"
        )
    }

    private fun startActivitySafely(
        intent: Intent,
        labelEn: String,
        labelBn: String,
        actionTag: String
    ): DeviceActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            DeviceActionResult.Success(
                messageEn = "Opened $labelEn.",
                messageBn = "$labelBn খোলা হয়েছে।",
                actionTag = actionTag
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Activity not found for $labelEn", e)
            DeviceActionResult.Unavailable(
                messageEn = "$labelEn is unavailable on this device.",
                messageBn = "এই ডিভাইসে $labelBn খোলা যাচ্ছে না।"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException launching $labelEn", e)
            DeviceActionResult.Failure(
                messageEn = "Android security settings prevented opening $labelEn.",
                messageBn = "নিরাপত্তা অনুমতির কারণে $labelBn খোলা যায়নি।"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error launching $labelEn", e)
            DeviceActionResult.Failure(
                messageEn = "Could not open $labelEn.",
                messageBn = "$labelBn চালু করা যায়নি।"
            )
        }
    }

    internal fun resolveLaunchIntentFor(appId: SupportedAppId): Intent? {
        val pm = appContext.packageManager
        return when (appId) {
            SupportedAppId.SETTINGS -> resolveActionIntent(pm, Intent(Settings.ACTION_SETTINGS))
            SupportedAppId.PHONE -> resolveActionIntent(pm, Intent(Intent.ACTION_DIAL))
            SupportedAppId.CAMERA -> resolveActionIntent(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            SupportedAppId.CLOCK -> resolveActionIntent(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            SupportedAppId.CALCULATOR -> resolveCategoryAppIntent(
                pm = pm,
                category = Intent.CATEGORY_APP_CALCULATOR,
                knownPackages = KNOWN_CALCULATOR_PACKAGES
            )
            SupportedAppId.CALENDAR -> resolveCategoryAppIntent(
                pm = pm,
                category = Intent.CATEGORY_APP_CALENDAR
            )
            SupportedAppId.BROWSER -> resolveCategoryAppIntent(
                pm = pm,
                category = Intent.CATEGORY_APP_BROWSER
            )
            SupportedAppId.MESSAGES -> resolveCategoryAppIntent(
                pm = pm,
                category = Intent.CATEGORY_APP_MESSAGING
            )
            SupportedAppId.MAPS -> resolveCategoryAppIntent(
                pm = pm,
                category = Intent.CATEGORY_APP_MAPS,
                knownPackages = KNOWN_MAPS_PACKAGES
            ) ?: resolveActionIntent(pm, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
        }
    }

    private fun resolveActionIntent(pm: PackageManager, intent: Intent): Intent? {
        val defaultMatches = queryActivitiesCompat(pm, intent, PackageManager.MATCH_DEFAULT_ONLY)
        val matches = defaultMatches.ifEmpty { queryActivitiesCompat(pm, intent, 0) }
        val usable = matches.firstOrNull { isUsableResolveInfo(it) } ?: return null

        val pkg = usable.activityInfo?.packageName
        if (pkg == "android") {
            return Intent(intent)
        }
        return Intent(intent).apply {
            if (!pkg.isNullOrBlank() && !usable.activityInfo.name.isNullOrBlank()) {
                component = ComponentName(pkg, usable.activityInfo.name)
            }
        }
    }

    private fun resolveCategoryAppIntent(
        pm: PackageManager,
        category: String,
        knownPackages: List<String> = emptyList()
    ): Intent? {
        val directCategoryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(category)
        }
        val directMatches = queryActivitiesCompat(pm, directCategoryIntent, 0)
            .filter { isUsableResolveInfo(it) }

        if (directMatches.isNotEmpty()) {
            val chosen = directMatches.first()
            val pkg = chosen.activityInfo.packageName
            val activityName = chosen.activityInfo.name
            if (pkg == "android") {
                return directCategoryIntent
            }
            val launcherIntent = pm.getLaunchIntentForPackage(pkg)
            if (launcherIntent != null) {
                return launcherIntent
            }
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(category)
                component = ComponentName(pkg, activityName)
            }
        }

        val selectorIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        val selectorComponent = selectorIntent.resolveActivity(pm)
        if (selectorComponent != null && selectorComponent.packageName != appContext.packageName) {
            if (selectorComponent.packageName != "android") {
                pm.getLaunchIntentForPackage(selectorComponent.packageName)?.let { return it }
            }
            return selectorIntent
        }

        for (pkg in knownPackages) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                return launchIntent
            }
        }

        return null
    }

    private fun isUsableResolveInfo(info: ResolveInfo): Boolean {
        val activityInfo = info.activityInfo ?: return false
        if (activityInfo.packageName == appContext.packageName) return false
        if (activityInfo.packageName == "android") return true
        return activityInfo.exported
    }

    @Suppress("DEPRECATION")
    private fun queryActivitiesCompat(
        pm: PackageManager,
        intent: Intent,
        flags: Int
    ): List<ResolveInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(flags.toLong())
                )
            } else {
                pm.queryIntentActivities(intent, flags)
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentActivities failed for $intent", e)
            emptyList()
        }
    }

    private fun SupportedAppId.displayEn(): String = when (this) {
        SupportedAppId.SETTINGS -> "Settings"
        SupportedAppId.PHONE -> "Phone"
        SupportedAppId.CALCULATOR -> "Calculator"
        SupportedAppId.CALENDAR -> "Calendar"
        SupportedAppId.CAMERA -> "Camera"
        SupportedAppId.CLOCK -> "Clock"
        SupportedAppId.BROWSER -> "Browser"
        SupportedAppId.MESSAGES -> "Messages"
        SupportedAppId.MAPS -> "Maps"
    }

    private fun SupportedAppId.displayBn(): String = when (this) {
        SupportedAppId.SETTINGS -> "সেটিংস"
        SupportedAppId.PHONE -> "ফোন"
        SupportedAppId.CALCULATOR -> "ক্যালকুলেটর"
        SupportedAppId.CALENDAR -> "ক্যালেন্ডার"
        SupportedAppId.CAMERA -> "ক্যামেরা"
        SupportedAppId.CLOCK -> "ঘড়ি"
        SupportedAppId.BROWSER -> "ব্রাউজার"
        SupportedAppId.MESSAGES -> "মেসেজ"
        SupportedAppId.MAPS -> "ম্যাপস"
    }

    companion object {
        private const val TAG = "AppLauncherController"

        private val KNOWN_CALCULATOR_PACKAGES = listOf(
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.calculator",
            "com.miui.calculator",
            "com.oneplus.calculator",
            "com.coloros.calculator",
            "com.asus.calculator",
            "com.motorola.calculator",
            "com.vivo.calculator"
        )

        private val KNOWN_MAPS_PACKAGES = listOf(
            "com.google.android.apps.maps"
        )
    }
}
