package com.rushi.modulesmanager.data

import android.os.Build
import org.json.JSONObject
import java.util.Locale

/**
 * Picks the APK inside a release that fits this device. Release assets carry their variant in
 * the file name (`arm64`, `universal`, `minSdk23`, `debug`), which is enough to avoid handing an
 * incompatible or x86 build to a phone. APK contents are checked again after download.
 */
object ApkVariants {
    private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val ABI_ALIASES = mapOf(
        "arm64" to "arm64-v8a",
        "aarch64" to "arm64-v8a",
        "arm64v8a" to "arm64-v8a",
        "armv8" to "arm64-v8a",
        "armeabi" to "armeabi-v7a",
        "armv7" to "armeabi-v7a",
        "armv7a" to "armeabi-v7a",
        "x64" to "x86_64",
    )
    private val UNIVERSAL_WORDS = listOf("universal", "allabi", "all-abi", "noarch")
    private val DEBUG_WORDS = listOf("debug", "dev")
    private val BUNDLED_WORDS = listOf("wearos", "wear_app", "bridge", "provider", "demo")

    data class Trait(
        val asset: RepoAsset,
        val abis: Set<String>,
        val universal: Boolean,
        val minSdk: Int?,
        val debug: Boolean,
        val bundled: Boolean,
        /** Vendor OS token such as `hyperos3`, `oneui8`, `coloros15` or `originos5`. */
        val vendor: String? = null,
        /** Marketing Android version from the name, such as 16 for `A16`. */
        val android: Int? = null,
        /** libxposed API level from `api102`. */
        val libxposedApi: Int? = null,
    )

    /** A remembered variant, independent of one release's file name. */
    data class Preference(
        val name: String,
        val traits: String,
        val flavor: String,
    )

    fun traits(asset: RepoAsset): Trait {
        val name = asset.name.lowercase()
        val compact = name.replace('-', '_')
        val abis = ABIS.filter { abi ->
            name.contains(abi) || name.contains(abi.replace('-', '_'))
        }.toSet() + ABI_ALIASES.filterKeys { alias -> compact.contains(alias) }.values
        // `api102` is a libxposed API level, not a minimum Android SDK.
        val minSdk = Regex("""(?:minsdk|min_sdk)[-_]?(\d{2,3})""")
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return Trait(
            asset = asset,
            abis = abis,
            universal = UNIVERSAL_WORDS.any { name.contains(it) },
            minSdk = minSdk,
            debug = DEBUG_WORDS.any { name.substringBeforeLast('.').contains(it) },
            bundled = BUNDLED_WORDS.any { name.contains(it) },
            vendor = vendorToken(name),
            android = ANDROID_VERSION.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            libxposedApi = LIBXPOSED_API.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull(),
        )
    }

    /** Short labels for the APK picker, such as `arm64 - HyperOS 3 - libxposed API 102`. */
    fun hints(asset: RepoAsset): List<String> {
        val trait = traits(asset)
        return buildList {
            if (trait.abis.isNotEmpty()) {
                add(trait.abis.sorted().joinToString("/") { abiLabel(it) })
            } else if (trait.universal) {
                add("universal")
            }
            trait.vendor?.let { add(vendorLabel(it)) }
            trait.android?.let { add("Android $it") }
            trait.libxposedApi?.let { add("libxposed API $it") }
            trait.minSdk?.let { add("minSdk $it") }
            if (trait.debug) add("debug")
        }
    }

    /**
     * The usable assets with the highest score. More than one means the variants fit this device
     * equally well, so the user should choose instead of the app guessing.
     */
    fun bestChoices(
        assets: List<RepoAsset>,
        sdk: Int = Build.VERSION.SDK_INT,
        deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): List<RepoAsset> {
        if (assets.isEmpty()) return emptyList()
        val scored = assets
            .map { asset -> traits(asset) to score(traits(asset), sdk, deviceAbis) }
            .filter { (trait, score) -> score >= 0 && !trait.bundled }
        if (scored.isEmpty()) return assets.filterNot { traits(it).bundled }
        val best = scored.maxOf { (_, score) -> score }
        return scored
            .filter { (_, score) -> score == best }
            // Download count is a weak tie-break only; it never decides against the device fit.
            .sortedByDescending { (trait, _) -> trait.asset.downloadCount }
            .map { (trait, _) -> trait.asset }
    }

    /** Returns the asset to install, or null when every asset is unusable on this device. */
    fun pick(
        assets: List<RepoAsset>,
        sdk: Int = Build.VERSION.SDK_INT,
        deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): RepoAsset? = bestChoices(assets, sdk, deviceAbis).firstOrNull()

    fun preference(asset: RepoAsset): Preference {
        val trait = traits(asset)
        return Preference(
            name = asset.name,
            traits = traitKey(trait),
            flavor = flavor(asset.name),
        )
    }

    /** Finds the remembered variant in another release, or null when it cannot be identified. */
    fun match(
        preference: Preference,
        assets: List<RepoAsset>,
        sdk: Int = Build.VERSION.SDK_INT,
        deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): RepoAsset? {
        val usable = assets.filter { asset ->
            val trait = traits(asset)
            score(trait, sdk, deviceAbis) >= 0 && !trait.bundled
        }
        if (usable.isEmpty()) return null
        usable.firstOrNull { it.name == preference.name }?.let { return it }
        usable.firstOrNull { it.name.equals(preference.name, ignoreCase = true) }?.let { return it }

        val sameFlavor = if (preference.flavor.isNotEmpty()) {
            usable.filter { flavor(it.name) == preference.flavor }
        } else {
            emptyList()
        }
        val sameTraits = usable.filter { traitKey(traits(it)) == preference.traits }
        (sameFlavor.filter { traitKey(traits(it)) == preference.traits })
            .takeIf { it.size == 1 }
            ?.firstOrNull()
            ?.let { return it }
        sameFlavor.takeIf { it.size == 1 }?.firstOrNull()?.let { return it }
        sameTraits.takeIf { it.size == 1 }?.firstOrNull()?.let { return it }
        return null
    }

    fun encode(preference: Preference): String = JSONObject().apply {
        put("name", preference.name)
        put("traits", preference.traits)
        put("flavor", preference.flavor)
    }.toString()

    fun decode(value: String?): Preference? = runCatching {
        val obj = JSONObject(value ?: return null)
        Preference(
            name = obj.optString("name"),
            traits = obj.optString("traits"),
            flavor = obj.optString("flavor"),
        ).takeIf { it.name.isNotBlank() }
    }.getOrNull()

    private fun traitKey(trait: Trait): String = buildString {
        append(trait.abis.sorted().joinToString(","))
        append('|')
        append(trait.minSdk ?: "")
        append('|')
        if (trait.universal) append('u')
        if (trait.debug) append('d')
        trait.vendor?.let { append("|$it") }
        trait.android?.let { append("|a$it") }
        trait.libxposedApi?.let { append("|api$it") }
    }

    /** The file name with version and build numbers removed, such as "os2_module_release". */
    private fun flavor(name: String): String = name
        .substringBeforeLast('.')
        .lowercase(Locale.ROOT)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() && !it.all(Char::isDigit) }
        .joinToString("_")


    private fun score(trait: Trait, sdk: Int, deviceAbis: List<String>): Int {
        if (trait.minSdk != null && trait.minSdk > sdk) return -1
        var score = 0
        val matchingAbi = trait.abis.firstOrNull { it in deviceAbis }
        score += when {
            matchingAbi != null -> 100 - deviceAbis.indexOf(matchingAbi)
            trait.abis.isNotEmpty() -> return -1
            trait.universal -> 50
            else -> 10
        }
        if (trait.minSdk != null) score += 20 + trait.minSdk / 10
        if (trait.debug) score -= 15
        if (trait.libxposedApi != null) score += 10
        val deviceVendor = detectVendor()
        if (deviceVendor != null && trait.vendor != null) {
            val (family, major) = deviceVendor
            if (trait.vendor.startsWith(family)) {
                val traitMajor = trait.vendor.removePrefix(family).toIntOrNull()
                score += if (major != null && traitMajor == major) 40 else 20
            }
        }
        val deviceAndroid = androidVersion(Build.VERSION.SDK_INT)
        if (trait.android != null && trait.android == deviceAndroid) score += 30
        return score
    }

    private val vendorRegexes = listOf(
        "hyperos" to Regex("hyperos[_-]?(\\d+)"),
        "miui" to Regex("miui[_-]?(\\d+)"),
        "oneui" to Regex("oneui[_-]?(\\d+)"),
        "coloros" to Regex("coloros[_-]?(\\d+)"),
        "originos" to Regex("originos[_-]?(\\d+)"),
    )
    private val VENDOR_SHORT = Regex("(?:^|[^a-z0-9])os([23])(?![0-9])")
    private val ANDROID_VERSION = Regex("(?:^|[^a-z0-9])(?:android|a)[_-]?(1[2-9])(?![0-9])")
    private val LIBXPOSED_API = Regex("(?:^|[^a-z0-9])api[_-]?(\\d{2,3})(?![0-9])")

    private fun vendorToken(name: String): String? {
        vendorRegexes.forEach { (family, regex) ->
            val major = regex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (major != null) return family + major
        }
        if (name.contains("hyperos")) return "hyperos"
        if (name.contains("miui")) return "miui"
        val short = VENDOR_SHORT.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (short != null) return "hyperos$short"
        return null
    }

    /** Xiaomi, Samsung, OPPO/OnePlus/realme and vivo use different OS-specific APKs. */
    private fun detectVendor(): Pair<String, Int?>? {
        val maker = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        val build = (Build.VERSION.INCREMENTAL + " " + Build.DISPLAY).lowercase(Locale.ROOT)
        return when {
            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") -> {
                val miui = Regex("miui[_-]?(\\d+)").find(build)?.groupValues?.get(1)?.toIntOrNull()
                if (build.contains("miui") && miui != null) {
                    "miui" to miui
                } else {
                    "hyperos" to Regex("hyperos[_-]?(\\d+)")
                        .find(build)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            maker.contains("samsung") -> "oneui" to Regex("oneui[_-]?(\\d+)")
                .find(build)?.groupValues?.get(1)?.toIntOrNull()
            maker.contains("oppo") || maker.contains("oneplus") || maker.contains("realme") ->
                "coloros" to Regex("coloros[_-]?(\\d+)")
                    .find(build)?.groupValues?.get(1)?.toIntOrNull()
            maker.contains("vivo") || maker.contains("iqoo") -> "originos" to Regex("originos[_-]?(\\d+)")
                .find(build)?.groupValues?.get(1)?.toIntOrNull()
            else -> null
        }
    }

    private fun abiLabel(abi: String): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm32"
        else -> abi
    }

    private fun vendorLabel(token: String): String {
        val families = listOf(
            "hyperos" to "HyperOS",
            "oneui" to "One UI",
            "miui" to "MIUI",
            "coloros" to "ColorOS",
            "originos" to "OriginOS",
        )
        families.forEach { (key, label) ->
            if (token.startsWith(key)) {
                val major = token.removePrefix(key).toIntOrNull()
                return if (major == null) label else "$label $major"
            }
        }
        return token
    }

    /** Android marketing version for an API level, matching release names such as A16. */
    fun androidVersion(sdk: Int): Int? = when {
        sdk >= 33 -> sdk - 20
        sdk == 32 || sdk == 31 -> 12
        sdk == 30 -> 11
        sdk == 29 -> 10
        sdk == 28 -> 9
        sdk == 27 || sdk == 26 -> 8
        sdk >= 21 -> 5 + (sdk - 21) / 2
        else -> null
    }
}

/** The asset a single-tap install should use. Falls back to the first APK. */
fun RepoRelease.preferredAsset(): RepoAsset? =
    ApkVariants.pick(apkAssets) ?: apkAssets.firstOrNull()
