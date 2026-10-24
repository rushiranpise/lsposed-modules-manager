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
        val minSdk = Regex("""(?:minsdk|min_sdk|api)[-_]?(\d{2,3})""")
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
        )
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
        return scored.filter { (_, score) -> score == best }.map { (trait, _) -> trait.asset }
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
        return score
    }
}

/** The asset a single-tap install should use. Falls back to the first APK. */
fun RepoRelease.preferredAsset(): RepoAsset? =
    ApkVariants.pick(apkAssets) ?: apkAssets.firstOrNull()
