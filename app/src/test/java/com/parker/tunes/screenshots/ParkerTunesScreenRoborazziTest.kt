package com.parker.tunes.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.parker.tunes.ui.preview.ParkerTunesPreviewScreen
import com.parker.tunes.ui.preview.PreviewState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParkerTunesScreenRoborazziTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(screen: String, state: PreviewState) {
        composeRule.setContent { ParkerTunesPreviewScreen(screen, state) }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/screens/${screen.replace(" ", "")}_${state.name}.png"
        )
    }

    @Test
    fun homeScreenStates() {
        capture("Home", PreviewState.Loading)
        capture("Home", PreviewState.Empty)
        capture("Home", PreviewState.Loaded)
    }

    @Test
    fun exploreScreenStates() {
        capture("Explore", PreviewState.Loading)
        capture("Explore", PreviewState.Empty)
        capture("Explore", PreviewState.Loaded)
    }

    @Test
    fun searchScreenStates() {
        capture("Search", PreviewState.Loading)
        capture("Search", PreviewState.Empty)
        capture("Search", PreviewState.Loaded)
    }

    @Test
    fun libraryScreenStates() {
        capture("Library", PreviewState.Loading)
        capture("Library", PreviewState.Empty)
        capture("Library", PreviewState.Loaded)
    }

    @Test
    fun nowplayingScreenStates() {
        capture("NowPlaying", PreviewState.Loading)
        capture("NowPlaying", PreviewState.Empty)
        capture("NowPlaying", PreviewState.Loaded)
    }

    @Test
    fun equalizerScreenStates() {
        capture("Equalizer", PreviewState.Loading)
        capture("Equalizer", PreviewState.Empty)
        capture("Equalizer", PreviewState.Loaded)
    }

    @Test
    fun settingsScreenStates() {
        capture("Settings", PreviewState.Loading)
        capture("Settings", PreviewState.Empty)
        capture("Settings", PreviewState.Loaded)
    }

    @Test
    fun addonsScreenStates() {
        capture("Addons", PreviewState.Loading)
        capture("Addons", PreviewState.Empty)
        capture("Addons", PreviewState.Loaded)
    }

    @Test
    fun artistScreenStates() {
        capture("Artist", PreviewState.Loading)
        capture("Artist", PreviewState.Empty)
        capture("Artist", PreviewState.Loaded)
    }

    @Test
    fun albumScreenStates() {
        capture("Album", PreviewState.Loading)
        capture("Album", PreviewState.Empty)
        capture("Album", PreviewState.Loaded)
    }

    @Test
    fun listeningstatsScreenStates() {
        capture("ListeningStats", PreviewState.Loading)
        capture("ListeningStats", PreviewState.Empty)
        capture("ListeningStats", PreviewState.Loaded)
    }

}
