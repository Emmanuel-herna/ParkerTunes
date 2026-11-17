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

## Implemented foundation
- Compose Search, Now Playing, Library, and Settings screens
- Media3 ExoPlayer session service with audio-focus and headphone-disconnect handling
- Media notification support through Media3's `MediaSessionService`
- Room storage for playlists, playlist tracks, favorites, and play history
- Playlist creation and adding search results to playlists
- Persistent light/dark appearance preference
- Song artwork display when supplied by the provider
- Replaceable `MusicApi` interface with a safe empty demo implementation

## Music provider status
The demo `MusicApi` intentionally returns no search results and does not provide stream URLs. To enable catalogue search and playback, implement `MusicApi` in `app/src/main/java/com/parker/tunes/data/MusicApi.kt` using a legitimate provider/API and inject that implementation in `ParkerTunesApp`.

Only play audio you have permission to access. Do not scrape services or bypass access controls.

## Spotify metadata (optional)
ParkerTunes can use Spotify catalogue search metadata and artwork as an optional search fallback. Audio playback remains on the existing YouTube Music/NewPipe provider. Configure the Spotify Client ID and Client Secret in **Settings → Spotify metadata**. Credentials are stored locally in DataStore for this personal-use build; secrets embedded in an Android app can be extracted, so move them to a backend before distributing the app publicly. Empty credentials silently disable the Spotify fallback.

Spotify attribution: Search metadata & artwork © Spotify. Spotify is a trademark of Spotify AB. ParkerTunes is not affiliated with Spotify. Spotify-sourced search rows include a link back to the matching Spotify track. Spotify metadata and artwork must be used in accordance with the [Spotify Developer Policy](https://developer.spotify.com/policy) and [Branding Guidelines](https://developer.spotify.com/documentation/design).

## Cloud build (no PC required)
A GitHub Actions workflow is included at `.github/workflows/android-build.yml`. It builds a debug APK whenever code is pushed to `main`, and can also be started manually from the repository's Actions tab using **Android build** → **Run workflow**.

When the run finishes successfully, open the workflow run, scroll to **Artifacts**, and download **ParkerTunes-debug**. Extract the ZIP on your Android phone and open `app-debug.apk` to install it. Android may ask you to allow your browser or file manager to install apps from that source. Only install APKs built from this repository that you trust.

## Build status
The cloud workflow has just been added; its first run still needs to complete successfully before the APK can be considered verified. The source has not been tested on a physical device yet.

## Listen Together

ParkerTunes includes a Listen Together screen for shared listening sessions. Open **Together** in the bottom navigation, enter your party server URL and display name, then create a six-character room code. Other devices use the same server URL and code to join.

The Go party service lives in [backend/](backend/). It synchronizes track selection, play/pause, seeking, member presence and the shared queue; each device resolves its own audio stream through ParkerTunes' existing music provider. To deploy the service, follow [backend/README.md](backend/README.md) and use the root [Render Blueprint](render.yaml).

The party server is currently single-instance and in-memory. A server restart or an idle Render free-tier sleep ends active rooms. ParkerTunes does not yet have Google sign-in, so party display names are device-provided and are not verified against Google accounts.
