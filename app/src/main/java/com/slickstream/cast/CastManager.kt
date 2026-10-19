package com.slickstream.cast

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.view.KeyEvent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper around [CastContext]. Initialises the Cast framework lazily and only when Google
 * Play services is present (so Android TV / Play-less devices simply report cast unavailable),
 * and selects the physical LAN interface for the phone's Cast media bridge.
 */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // CastPlayer.release() ends the SDK session, even with stopReceiver=false. Own one adapter
    // for the app lifetime so leaving a movie does not discard the TV chosen while browsing.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private var sharedPlayer: CastPlayer? = null
    private var playbackOwner: Any? = null
    private val volumeKeys = CastVolumeKeys()

    data class Volume(val level: Float, val muted: Boolean)

    fun volumeSnapshot(): Volume? = runCatching {
        val session = castContext?.sessionManager?.currentCastSession ?: return null
        val media = session.remoteMediaClient?.mediaStatus
        Volume((session.volume * (media?.streamVolume ?: 1.0)).toFloat().coerceIn(0f, 1f),
            session.isMute || media?.isMute == true)
    }.getOrNull()

    /** Both the on-screen slider and hardware keys change the same combined receiver volume. */
    fun setVolume(volume: Float) {
        runCatching {
            val session = castContext?.sessionManager?.currentCastSession ?: return
            val level = volume.coerceIn(0f, 1f).toDouble()
            val deviceLevel = session.volume
            if (deviceLevel > 0.0 && level <= deviceLevel) {
                session.remoteMediaClient?.setStreamVolume(level / deviceLevel)
            } else {
                session.remoteMediaClient?.setStreamVolume(1.0)
                runCatching { session.volume = level }
            }
            session.remoteMediaClient?.setStreamMute(false)
            runCatching { session.setMute(false) }
            volumeKeys.volumeRequested(level.toFloat())
        }.onFailure { Log.w(TAG, "Cast volume command failed", it) }
    }

    fun setMuted(muted: Boolean) {
        runCatching {
            val session = castContext?.sessionManager?.currentCastSession ?: return
            session.remoteMediaClient?.setStreamMute(muted)
            runCatching { session.setMute(muted) }
        }.onFailure { Log.w(TAG, "Cast mute command failed", it) }
    }

    fun dispatchVolumeKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) return false
        val session = castContext?.sessionManager?.currentCastSession
        // A selected TV alone must not steal local volume. Paused/buffering Cast media still counts.
        val receiver = session?.takeIf {
            playbackOwner != null && it.isConnected && it.remoteMediaClient?.hasMediaSession() == true
        }
        val volume = if (receiver != null) volumeSnapshot() else null
        return volumeKeys.handle(event.keyCode, event.action, event.repeatCount,
            receiver.takeIf { volume != null }, volume?.level ?: 0f,
            setVolume = ::setVolume, toggleMute = { setMuted(volume?.muted != true) })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun acquirePlayer(owner: Any, listener: SessionAvailabilityListener): CastPlayer? {
        val ctx = castContext ?: return null
        val player = sharedPlayer ?: CastPlayer(ctx, CastMediaItemConverter()).also { sharedPlayer = it }
        playbackOwner = owner
        player.setSessionAvailabilityListener(listener)
        return player
    }

    /** Stop this video's media before its HTTP bridge closes, but keep the selected TV connected. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun detachPlayer(owner: Any, stopMedia: Boolean) {
        if (playbackOwner !== owner) return
        playbackOwner = null
        sharedPlayer?.setSessionAvailabilityListener(null)
        if (stopMedia) runCatching { castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.stop() }
    }

    fun connectionSnapshot(): CastConnectionState {
        val ctx = castContext ?: return CastConnectionState(available = false)
        return CastConnectionState(
            connected = ctx.castState == CastState.CONNECTED,
            connecting = ctx.castState == CastState.CONNECTING,
            deviceName = ctx.sessionManager.currentCastSession?.castDevice?.friendlyName,
        )
    }

    /** Collected on main; listen even before any player exists. Discovery starts with the chooser. */
    val connection = callbackFlow {
        val ctx = castContext
        val listener = CastStateListener { trySend(connectionSnapshot()) }
        ctx?.addCastStateListener(listener)
        trySend(connectionSnapshot())
        awaitClose { ctx?.removeCastStateListener(listener) }
    }

    /** Null when Play services / Cast is unavailable on this device. */
    val castContext: CastContext? by lazy {
        try {
            val available = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
            if (available) CastContext.getSharedInstance(context) else null
        } catch (t: Throwable) {
            Log.w(TAG, "Cast unavailable", t)
            null
        }
    }

    val isAvailable: Boolean get() = castContext != null

    fun isConnected(): Boolean =
        castContext?.castState == CastState.CONNECTED

    /** End the current Cast session (Stop Casting). Must run on the main thread (callers do). */
    fun stopCasting(stopReceiver: Boolean = true) {
        runCatching { castContext?.sessionManager?.endCurrentSession(stopReceiver) }
            .onFailure { Log.w(TAG, "endCurrentSession failed", it) }
    }

    fun startHosting() {
        ContextCompat.startForegroundService(context, Intent(context, CastStreamingService::class.java))
    }

    fun stopHosting() {
        context.stopService(Intent(context, CastStreamingService::class.java))
    }

    /** Pick the physical LAN, never a cellular/VPN address that the receiver cannot reach. */
    fun lanAddress(): String? = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.allNetworks.asSequence()
            .filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        Log.w(TAG, "LAN address unavailable", t)
        null
    }

    private companion object {
        const val TAG = "CastManager"
    }
}
