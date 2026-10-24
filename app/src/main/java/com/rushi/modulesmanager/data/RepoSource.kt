package com.rushi.modulesmanager.data

enum class RepoFormat {
    /** The current paginated API: /module-list/{page}.json and /module/{package}.json. */
    MODERN,

    /** Detect between the paginated API and the older single modules.json document. */
    AUTO,

    /** A single daily repos.json document from the xposed-modules data repository. */
    REPOS_JSON,
}

data class RepoSource(
    val id: String,
    val name: String,
    val bases: List<String>,
    val format: RepoFormat = RepoFormat.REPOS_JSON,
    /** Commit feed for the data file, used to show when the index actually changed. */
    val updatedFeed: String? = null,
)

object DefaultSources {
    /** The daily Xposed module index maintained by the app author. */
    val official = RepoSource(
        id = "xposed-modules",
        name = "Xposed Modules",
        bases = listOf(
            "https://raw.githubusercontent.com/rushiranpise/xposed-modules/main/data/repos.json",
        ),
        format = RepoFormat.REPOS_JSON,
        updatedFeed = "https://github.com/rushiranpise/xposed-modules/commits/main.atom?path=data%2Frepos.json",
    )
}
