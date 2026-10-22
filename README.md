# Modules Manager 📦

An Android app for finding, installing and updating Xposed/Lsposed modules.

- Package: `com.rushi.modulesmanager`
- Android 8.1 (API 26) and newer
- ⬇️ [Latest release](https://github.com/rushiranpise/lsposed-modules-manager/releases)

## Features

- Browse over 2,000 module repositories from the daily [xposed-modules index](https://github.com/rushiranpise/xposed-modules).
- Search, sort by name or last update, and filter by updates, installed or not installed.
- See the installed version next to an available update.
- Open a module to read its README, its full GitHub release history and target apps.
- The Releases tab lists every published release, stable and pre-release, with their APKs.
- Translate module descriptions and READMEs on device, manually or automatically.
- Install with the system installer, a PackageInstaller session, or Shizuku for silent installs.
- Long press an installed module to uninstall it.
- The Installed tab lists every installed module, including ones missing from the index.
- Light, dark and system themes, Material You colors, AMOLED black and GitHub-style identicons.

## Translation

Translation runs on device with Google ML Kit, so no API key or account is needed.

- **Translate automatically** in Settings translates module descriptions when the list loads and the README when a module is opened.
- **Language** picks the target language, or keeps the device language.
- Each language pair downloads its model once, around 30 MB, and translations are cached per language.
- README translation rewrites only prose. Links, images, code blocks and inline code keep their exact syntax.
- Rows whose description is already in the target language hide the translate icon.

## Build

Requires JDK 21 and Android SDK 37.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Signed release APKs are built by the [release workflow](.github/workflows/release.yml) when a `v*` tag is pushed.

## Data

The app reads the daily index from:

`https://raw.githubusercontent.com/rushiranpise/xposed-modules/main/data/repos.json`

Each entry provides the package, summary, target scope and latest APK release. Installed modules are checked against GitHub releases when the app starts, then at most once every six hours, so releases published after the daily snapshot still appear. The index has no README and keeps only the newest release per channel, so opening a module fetches its README from raw.githubusercontent.com and its release history from the GitHub API. Unauthenticated GitHub API rate limits can delay the history until the quota resets.

The Installed tab counts every installed module. The Installed filter on the Modules screen only counts modules that are in the index.

## License

GPL-3.0. See [LICENSE](LICENSE).
