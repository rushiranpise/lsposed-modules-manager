package com.rushi.modulesmanager.data

enum class InstallerType(
    val key: String,
    val label: String,
    val description: String,
) {
    SYSTEM(
        "system",
        "System installer",
        "Open the APK in the system package installer",
    ),
    SESSION(
        "session",
        "Session installer",
        "Use a PackageInstaller session and the system confirmation",
    ),
    SHIZUKU(
        "shizuku",
        "Shizuku",
        "Install silently through Shizuku, which must be running and allowed",
    ),
    ROOT(
        "root",
        "Root",
        "Install silently through a root shell. Your root manager must allow Modules Manager.",
    );

    companion object {
        fun fromKey(key: String?): InstallerType = entries.firstOrNull { it.key == key } ?: SESSION
    }
}
