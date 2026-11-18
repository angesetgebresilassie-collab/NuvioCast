package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.cast.CastButton
import com.nuvio.app.features.cast.CastMedia

@Composable
internal actual fun PlatformCastButton(
    request: () -> PlayerCastRequest?,
    onCastRequested: () -> Unit,
    modifier: Modifier,
) {
    CastButton(
        mediaProvider = {
            request()?.let { r ->
                // Torrent/p2p sources resolve to a loopback URL the receiver can't reach, so those
                // always go through the phone's local server.
                val loopback = r.url.contains("://127.0.0.1") || r.url.contains("://localhost")
                val media = CastMedia(
                    url = r.url,
                    title = r.title,
                    headers = r.headers,
                    posterUrl = r.posterUrl,
                    durationMs = r.durationMs,
                    startPositionMs = r.positionMs,
                    forceProxy = loopback,
                )
                onCastRequested()
                media
            }
        },
        modifier = modifier,
    )
}
