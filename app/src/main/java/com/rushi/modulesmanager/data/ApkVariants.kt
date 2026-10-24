package com.rushi.modulesmanager.data

import android.os.Build

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
