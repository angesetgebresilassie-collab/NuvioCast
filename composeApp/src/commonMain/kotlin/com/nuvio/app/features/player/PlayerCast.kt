package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the player hands to the platform cast button at the moment a device is picked. */
internal data class PlayerCastRequest(
    val url: String,
    val title: String,
    val headers: Map<String, String>,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Cast icon + popup. Android implements it with the cast engine; other platforms draw nothing.
 * [onCastRequested] fires when the user picks a device, so the local player can pause.
 */
@Composable
internal expect fun PlatformCastButton(
    request: () -> PlayerCastRequest?,
    onCastRequested: () -> Unit,
    modifier: Modifier,
)
