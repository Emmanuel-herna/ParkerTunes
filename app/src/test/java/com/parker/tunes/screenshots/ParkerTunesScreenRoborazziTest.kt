package com.parker.tunes.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.parker.tunes.ui.preview.ParkerTunesPreviewScreen
import com.parker.tunes.ui.preview.PreviewState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParkerTunesScreenRoborazziTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun capture(screen: String, state: PreviewState) {
        composeRule.activity.setContent {
            ParkerTunesPreviewScreen(screen, state)
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun homeLoading() = capture("Home", PreviewState.Loading)

    @Test
    fun homeEmpty() = capture("Home", PreviewState.Empty)

    @Test
    fun homeLoaded() = capture("Home", PreviewState.Loaded)

    @Test
    fun exploreLoading() = capture("Explore", PreviewState.Loading)

    @Test
    fun exploreEmpty() = capture("Explore", PreviewState.Empty)

    @Test
    fun exploreLoaded() = capture("Explore", PreviewState.Loaded)

    @Test
    fun searchLoading() = capture("Search", PreviewState.Loading)

    @Test
    fun searchEmpty() = capture("Search", PreviewState.Empty)

    @Test
    fun searchLoaded() = capture("Search", PreviewState.Loaded)

    @Test
    fun libraryLoading() = capture("Library", PreviewState.Loading)

    @Test
    fun libraryEmpty() = capture("Library", PreviewState.Empty)

    @Test
    fun libraryLoaded() = capture("Library", PreviewState.Loaded)

    @Test
    fun nowPlayingLoading() = capture("Now Playing", PreviewState.Loading)

    @Test
    fun nowPlayingEmpty() = capture("Now Playing", PreviewState.Empty)

    @Test
    fun nowPlayingLoaded() = capture("Now Playing", PreviewState.Loaded)

    @Test
    fun equalizerLoading() = capture("Equalizer", PreviewState.Loading)

    @Test
    fun equalizerEmpty() = capture("Equalizer", PreviewState.Empty)

    @Test
    fun equalizerLoaded() = capture("Equalizer", PreviewState.Loaded)

    @Test
    fun settingsLoading() = capture("Settings", PreviewState.Loading)

    @Test
    fun settingsEmpty() = capture("Settings", PreviewState.Empty)

    @Test
    fun settingsLoaded() = capture("Settings", PreviewState.Loaded)

    @Test
    fun addonsLoading() = capture("Addons", PreviewState.Loading)

    @Test
    fun addonsEmpty() = capture("Addons", PreviewState.Empty)

    @Test
    fun addonsLoaded() = capture("Addons", PreviewState.Loaded)

    @Test
    fun artistLoading() = capture("Artist", PreviewState.Loading)

    @Test
    fun artistEmpty() = capture("Artist", PreviewState.Empty)

    @Test
    fun artistLoaded() = capture("Artist", PreviewState.Loaded)

    @Test
    fun albumLoading() = capture("Album", PreviewState.Loading)

    @Test
    fun albumEmpty() = capture("Album", PreviewState.Empty)

    @Test
    fun albumLoaded() = capture("Album", PreviewState.Loaded)

    @Test
    fun listeningStatsLoading() = capture("Listening Stats", PreviewState.Loading)

    @Test
    fun listeningStatsEmpty() = capture("Listening Stats", PreviewState.Empty)

    @Test
    fun listeningStatsLoaded() = capture("Listening Stats", PreviewState.Loaded)

}
