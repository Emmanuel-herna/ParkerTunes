package com.parker.tunes.data.sources

sealed class SourceHealth {
    data class Ok(val detail: String) : SourceHealth()
    data class Rejected(val reason: String) : SourceHealth()
    data class Unreachable(val reason: String) : SourceHealth()
}
