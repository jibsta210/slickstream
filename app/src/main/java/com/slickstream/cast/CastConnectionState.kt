package com.slickstream.cast

data class CastConnectionState(
    val available: Boolean = true,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceName: String? = null,
) {
    val label: String get() = when {
        connected -> "Connected to ${deviceName ?: "TV"}"
        connecting -> "Connecting to ${deviceName ?: "TV"}…"
        else -> "Watch on TV"
    }
}
