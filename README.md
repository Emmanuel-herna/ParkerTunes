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

## Build status
The source has been committed to GitHub, but it has not yet been compiled or run in Android Studio. Run Gradle sync and build the app before treating it as release-ready. Report any compiler output so it can be corrected.
