# SlickStream

A native **Android + Android TV** streaming app — a modern, polished replacement for *OnStream*.
Browse trending & new movies and shows from TMDB, sign in with Google, keep favourites and a
"Continue Watching" row, and **stream over BitTorrent** (sequential download) instead of fragile
file hosts — with on-device caching so paused/recent titles resume instantly. Search is
**voice-first** (built for the Google TV remote mic), with typing as a fallback.

> One codebase, two form factors: touch-first Compose UI on phones/tablets, a 10-foot
> D-pad/Compose-for-TV experience on Android TV / Google TV. The app detects the device at
> runtime (`UiModeManager`) and routes to `PhoneApp()` or `TvApp()`.

---

## Features

- 🎬 **Catalog** — Trending, Popular, Top Rated, New & Upcoming for Movies and TV (TMDB).
- 🔎 **Voice-first search** — big mic button driven by `SpeechRecognizer`; live partial transcripts; typing optional.
- ❤️ **Favourites** synced per Google account; **Continue Watching** with resume points.
- 🔐 **Google OAuth** via Credential Manager (`GetSignInWithGoogle`), guest browsing allowed.
- 🌀 **Torrent streaming** — `libtorrent4j` sequential download + a local HTTP range server bridged to Media3/ExoPlayer.
- 💾 **Smart cache** — recently watched torrents are kept (LRU, ~4 GB budget); pausing/exiting keeps the partial download so resume is instant.
- 🧩 **Pluggable sources** — a Stremio/Torrentio-compatible indexer resolves IMDB ids → torrent streams; the endpoint is **user-configurable** (point it at sources you're authorized to use).
- 📺 **Android TV** — immersive featured carousel, focus-scaling cards, D-pad transport controls, mic search.

## Architecture

```
com.slickstream
├── core/model        ← domain types (MediaItem, MediaDetails, StreamSource, StreamStatus, …)
├── core/repository   ← interfaces: Catalog / Library / Source / TorrentStreamer / Auth
├── core/common       ← Constants (Img.url, Tmdb, Indexer, Auth) backed by BuildConfig
├── di                ← CoreNetworkModule (Json + OkHttp) + per-feature Hilt modules
├── data/tmdb         ← Retrofit + kotlinx.serialization → CatalogRepository
├── data/local        ← Room (favourites + history) → LibraryRepository
├── data/source       ← indexer client → SourceRepository
├── data/torrent      ← libtorrent4j engine + NanoHTTPD stream server → TorrentStreamer
├── feature/auth      ← Google sign-in (Credential Manager) → AuthRepository
├── feature/home, details, search, favorites, profile, player   ← phone screens + ViewModels
├── ui/components     ← shared Compose UI kit (PosterCard, MediaRow, HeroBanner, …)
├── ui/theme          ← Brand palette + SlickStreamTheme
├── ui/PhoneApp       ← phone NavHost + bottom nav
├── tv                ← Compose-for-TV screens (TvApp + browse/details/search/player)
└── navigation        ← Routes + nav args
```

Patterns: **MVVM** (Hilt `@HiltViewModel` + Compose), repository interfaces in `core` with
implementations bound per Hilt module, Kotlin coroutines/`Flow` end to end. ViewModels are shared
between phone and TV — only the presentation differs.

## Setup

1. **Android Studio** (Ladybug or newer, JDK 17). Open the project; it will fetch the Gradle
   wrapper and sync. (If building from CLI, run `gradle wrapper` once to generate
   `gradle/wrapper/gradle-wrapper.jar`, then use `./gradlew`.)
2. Copy `local.properties.sample` → `local.properties` and fill in:
   - `TMDB_BEARER` (preferred) **or** `TMDB_API_KEY` — from <https://www.themoviedb.org/settings/api>.
   - `GOOGLE_WEB_CLIENT_ID` — an OAuth *Web application* client ID from the Google Cloud console
     (used as the `serverClientId` for Google sign-in). Leave blank to browse as guest.
   - `INDEXER_BASE_URL` — a Stremio-compatible stream indexer. Default `https://torrentio.strem.fun/`.
3. Build & run:
   ```
   ./gradlew :app:installDebug      # phone or TV device/emulator
   ```
   For the TV experience, use an **Android TV** emulator/device (the app shows on the TV launcher
   via `LEANBACK_LAUNCHER`).

### Required keys at a glance

| Key | Where | Needed for |
|-----|-------|-----------|
| `TMDB_BEARER` / `TMDB_API_KEY` | themoviedb.org | the entire catalog |
| `GOOGLE_WEB_CLIENT_ID` | Google Cloud OAuth | sign-in + synced favourites (optional) |
| `INDEXER_BASE_URL` | your indexer | resolving playable sources |

### Building on this machine (already set up ✅)

The toolchain is installed locally (no root) and the debug APK builds clean:

- **JDK 17**: `~/android-dev/jdk-17.0.19+10`
- **Android SDK**: `~/Android/Sdk` (platform-tools, platforms;android-35, build-tools;35.0.0) — also recorded in `local.properties` as `sdk.dir`
- One-line rebuild:
  ```sh
  JAVA_HOME=~/android-dev/jdk-17.0.19+10 ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug
  ```
  → `app/build/outputs/apk/debug/app-debug.apk` (~74 MB; bundles native libtorrent for arm64/arm/x86/x86_64).

  Install on a connected phone or Android TV device/emulator with `… ./gradlew installDebug`.

> Your system default JDK is 26, which AGP 8.7 can't run on — that's why the commands pin `JAVA_HOME` to the bundled JDK 17. Android Studio users can just set the Gradle JDK to 17 in settings.

## Tech

Kotlin · Jetpack Compose + Compose for TV · Hilt · Retrofit + kotlinx.serialization · Room ·
Media3/ExoPlayer · Coil · libtorrent4j · NanoHTTPD · Credential Manager.

## Legal

SlickStream is a media **player/streaming client**. It ships with **no content** and **no
default catalog of infringing material** — the source indexer is configurable and you are
responsible for pointing it only at content you are authorized to stream (public-domain
archives, your own seedboxes/legal torrents, etc.). BitTorrent is a general-purpose distribution
technology used by many legal services. Respect copyright and the laws in your jurisdiction.
