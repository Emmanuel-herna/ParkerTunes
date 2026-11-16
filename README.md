# ParkerTunes

A native Android music player starter built with Kotlin, Jetpack Compose, Media3 ExoPlayer, and Room.

## Requirements
- Android Studio (recent stable version)
- JDK 17
- Android SDK 35

## Open the project
1. Clone or download this repository.
2. Open the project folder in Android Studio.
3. Allow Gradle sync to finish.
4. Run the `app` configuration on an Android device or emulator.

## Current implementation
- Compose Search, Library, Now Playing, and Settings screens
- Media3 ExoPlayer playback engine
- Room entities/DAOs for playlists, playlist tracks, favorites, and play history
- Replaceable `MusicApi` interface with a demo stub
- Media notification and audio-focus handling through Media3

The demo API intentionally returns no search results until you connect a legitimate music catalogue/stream provider. Only play audio you have permission to access.

## Provider integration
Implement `MusicApi` in `data/MusicApi.kt` and inject the implementation in `ParkerTunesApp`. Do not scrape services or bypass access controls.
