# ParkerTunes

A native Android music player built with Kotlin, Jetpack Compose, Media3 ExoPlayer, and Room.

## Requirements

- Android Studio (recent stable version)
- JDK 17
- Android SDK 35

## Open the project

1. Clone or download this repository.
2. Open the project folder in Android Studio.
3. Allow Gradle sync to finish.
4. Run the `app` configuration on an Android device or emulator.

## Current app features

- Compose Search, Explore, Now Playing, Library, Settings, artist, album, and Listen Together screens
- Media3 ExoPlayer playback service with media-session notifications and audio-focus handling
- Room storage for playlists, playlist tracks, favorites, and play history
- Persistent light/dark appearance preference
- Download management and playback controls
- YouTube Music home shelves and music search through NewPipe's YouTube Music extractors
- YouTube Music-based related-song discovery with filters to avoid long mixes and non-song results
- Optional Spotify catalogue metadata and artwork fallback
- AM Lyrics 1.9.1 web component bundled locally in the Android app; lyric providers still require an internet connection

## Music provider notes

The Android music provider is `NewPipeMusicApi`. It uses NewPipe's dedicated `YoutubeMusicSearchExtractor` for music search and related-song discovery, and NewPipe's YouTube stream extractor to resolve playback streams. This is **not** the official YouTube Music Data API. It depends on upstream service behaviour and may need maintenance if YouTube changes its endpoints.

Recommendations are requested through the YouTube Music search path; Spotify is optional catalogue metadata/artwork only and does not supply playback audio.

Only play audio you have permission to access. Do not bypass access controls.

## Spotify metadata (optional)

ParkerTunes can use Spotify catalogue search metadata and artwork as an optional search fallback. Audio playback remains on the existing YouTube Music/NewPipe provider. Configure the Spotify Client ID and Client Secret in **Settings → Spotify metadata**. Credentials are stored locally in DataStore for this personal-use build; secrets embedded in an Android app can be extracted, so move this provider behind a backend before distributing the app publicly. Empty credentials silently disable the Spotify fallback.

Spotify attribution: Search metadata and artwork © Spotify. Spotify is a trademark of Spotify AB. ParkerTunes is not affiliated with Spotify. Spotify-sourced search rows include a link back to the matching Spotify track. Use Spotify metadata and artwork in accordance with the [Spotify Developer Policy](https://developer.spotify.com/policy) and [Branding Guidelines](https://developer.spotify.com/documentation/design).

## Lyrics

The Android lyrics view loads the AM Lyrics 1.9.1 web component from the app's local assets. The component bundle is generated from the source in `lyrics-web/src/` during the GitHub Actions build. The component itself is bundled locally; retrieving lyrics from its providers still requires network access. Timed lyric taps are forwarded to the Media3 player for seeking.

## Listen Together

The current Android Listen Together implementation uses Google sign-in through Android Credential Manager and Firebase Authentication, then synchronizes rooms, members, playback state, and queues through the configured Firebase Realtime Database. Room access is governed by `database.rules.json`.

The Go WebSocket service in [backend/](backend/) is a separate backend implementation/prototype; the Android client is **not currently wired to that Go service**. Its deployment notes are in [backend/README.md](backend/README.md). Do not assume deploying the Go service changes the current Android app's Listen Together transport.

## Cloud build (no PC required)

The GitHub Actions workflow at `.github/workflows/android-build.yml` builds a debug APK whenever code is pushed to `main`, and can also be started manually from the repository's Actions tab using **Android build** → **Run workflow**.

When a run finishes successfully, open the workflow run, scroll to **Artifacts**, and download **ParkerTunes-debug**. Extract the ZIP on your Android phone and open `app-debug.apk` to install it. Android may ask you to allow your browser or file manager to install apps from that source. Only install APKs built from this repository that you trust.

### Latest verified build

- Workflow run: [#44150636573](https://github.com/Emmanuel-herna/ParkerTunes/actions/runs/44150636573)
- Commit: `9a1065f90e5bc274a717dcde0423915e3993704a`
- Result: successful — lyrics bundle build, Android debug APK build/signing, Roborazzi screenshot tests, Firebase App Distribution, and Appetize upload all completed.
- The APK has not yet been verified on a physical Android device. Runtime playback, lyrics fetching/highlighting/seeking, and Google sign-in should still be tested on a real phone.

