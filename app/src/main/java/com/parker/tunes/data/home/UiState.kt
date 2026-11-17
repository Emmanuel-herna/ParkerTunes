package com.parker.tunes.data.home

/**
 * State exposed by the Home feed while its first page is loading, has failed,
 * or is ready to render. Continuation loading is tracked separately so an
 * already-rendered feed remains visible while more shelves are appended.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
}
