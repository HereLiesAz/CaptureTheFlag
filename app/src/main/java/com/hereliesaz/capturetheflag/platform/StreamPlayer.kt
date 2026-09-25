package com.hereliesaz.capturetheflag.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Plays a live stream: its segments, one after another, as they arrive. New segments are
 * appended to the playlist, so the player keeps going as long as the streamer does.
 */
@Composable
internal fun StreamPlayerView(urls: List<String>, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(urls) {
        val have = player.mediaItemCount
        urls.drop(have).forEach { player.addMediaItem(MediaItem.fromUri(it)) }
        if (have == 0 && urls.isNotEmpty()) player.prepare()
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}
