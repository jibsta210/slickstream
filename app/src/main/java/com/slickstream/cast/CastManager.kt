package com.slickstream.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [CastContext]. Initialises the Cast framework lazily and only when Google
 * Play services is present (so Android TV / Play-less devices simply report cast unavailable),
 * and rewrites a loopback stream URL to a LAN URL the Chromecast can actually reach.
 */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

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

    /**
     * The torrent stream server binds to all interfaces but reports a 127.0.0.1 URL for local
     * playback. A Chromecast is a different device, so swap loopback for this phone's LAN IP.
     * Returns null if no usable LAN address is found (e.g. no Wi-Fi).
     */
    fun toLanUrl(localUrl: String): String? {
        val ip = siteLocalIpv4() ?: return null
        return localUrl
            .replace("127.0.0.1", ip)
            .replace("localhost", ip)
    }

    private fun siteLocalIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress
            }
            ?.hostAddress
    } catch (t: Throwable) {
        Log.w(TAG, "siteLocalIpv4 failed", t)
        null
    }

    private companion object {
        const val TAG = "CastManager"
    }
}
