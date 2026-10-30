# Modules Manager 📦

An Android app for finding, installing and updating Xposed/Lsposed modules.

- Package: `io.github.rushiranpise.modulesmanager`
- Android 8.1 (API 26) and newer
- Versions up to 2.0.0 used `com.rushi.modulesmanager` and update separately.
- ⬇️ [Latest release](https://github.com/rushiranpise/lsposed-modules-manager/releases)

## What's new in 2.0

- A floating navigation pill with a gradient scrim, dynamic color, and a rail on tablets.
- A Library tab for favourites, recently viewed modules, and skipped or ignored updates.
- Per-update progress and cancel, pause and resume, retry after a dropped network, and 1 to 5 concurrent downloads.
- A Diagnostics page with device and build details, cache sizes, copy and share, and cleanup actions.
- The source picker now lists every repository the daily index holds for a package.
- A new app icon.

## Features

- Browse over 2,000 module repositories from the daily [xposed-modules index](https://github.com/rushiranpise/xposed-modules).
- Search, sort by name, last update, stars or recently added, and filter by updates, installed or not installed.
- See the installed version next to an available update. Each update row shows its own download percentage and can be cancelled without stopping the rest of the batch.
- Pause and resume a module download, or let it retry after a dropped connection and continue from the bytes already on disk. Settings can limit the app to 1 to 5 concurrent downloads.
- Open a module to read its README, its full GitHub release history and target apps.
- The Releases tab lists every published release, stable and pre-release, with their APKs.
- Translate module descriptions and READMEs, manually or automatically.
- Install with the system installer, a PackageInstaller session, Shizuku, or root for silent installs. Installs handed to an external installer are remembered and confirmed on the next scan.
- When a release ships several equally suitable APKs, a chooser reads the ABI, vendor OS, Android version and libxposed API level, and remembers the choice for later releases.
- Override the source repository of a module when the index points at a fork. The picker lists the alternate repositories from the index and marks the current one.
- Cards show a small sources badge when the index lists more than one repository for the package.
- Long press an installed module to uninstall it.
- The Installed tab lists every installed module, including ones missing from the index. Its filters are All, Updates, Skipped and Untracked.
- Star modules to keep them in the Library, together with recently viewed modules and skipped or ignored updates.
- Floating refresh, sort and scroll-to-top actions, a navigation rail on wide windows, and package events that keep the installed list current.
- Light, dark and system themes, Material You colors, AMOLED black and GitHub-style identicons.
- Diagnostics in Settings shows the build commit, device, module counts and cache sizes, with copy, share and clear actions.

The app also declares itself as a self-scoped Xposed module. It hooks nothing; the declaration lets LSPosed Manager install and update the app. Its scope list is intentionally the app itself, so LSPosed Manager shows no other targets.

## Translation

Translation goes through Google's public translate endpoint, so no API key or account is needed. The text leaves the device, and the endpoint needs a working network connection.

- **Translate automatically** in Settings translates module descriptions when the list loads and the README when a module is opened.
- **Language** picks the target language, or keeps the device language.
- Requests are batched, Google hosts are tried in turn if one fails, and translations are cached per language.
- README translation rewrites only prose. Links, images, code blocks and inline code keep their exact syntax.
- Rows whose description is already in the target language hide the translate icon.

## Build

Requires JDK 21 and Android SDK 37.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Signed release APKs are built by the [release workflow](.github/workflows/release.yml) when a `v*` tag is pushed. One APK covers every CPU architecture.

## Data

The app reads the daily index from:

`https://raw.githubusercontent.com/rushiranpise/xposed-modules/main/data/repos.json`

Each entry provides the package, summary, target scope and latest APK release. Installed modules are checked against GitHub releases when the app starts, then at most once every six hours, so releases published after the daily snapshot still appear. The index has no README and keeps only the newest release per channel, so opening a module fetches its README from raw.githubusercontent.com and its release history from the GitHub API. Unauthenticated GitHub API rate limits can delay the history until the quota resets.

The Installed tab counts every installed module. The Installed filter on the Modules screen only counts modules that are in the index. When the GitHub API rate limit is reached, manually linked repositories and live update checks fall back to the public releases Atom feed and its expanded asset list.

## License

GPL-3.0. See [LICENSE](LICENSE).
