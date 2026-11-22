package com.parker.tunes

import androidx.compose.runtime.Composable
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.ui.AppUiHost
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal AppUiHost adapter for Plyra.
 *
 * The imported player screen only needs this registry for shared queue/list
 * components. Plyra does not currently expose BitChord's library/download
 * stores, so these values safely use neutral defaults.
 */
internal object PlyraAppUiHost : AppUiHost {
    override val swipeToPlayNext = MutableStateFlow(false)
    override val pinnedPlaylists = MutableStateFlow<List<String>>(emptyList())
    override val librarySort = MutableStateFlow(LibrarySort.DEFAULT)

    @Composable
    override fun downloadedIds(): Set<String> = emptySet()
}
