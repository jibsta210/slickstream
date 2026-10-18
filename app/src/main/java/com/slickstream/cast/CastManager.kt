package com.slickstream.cast

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [CastContext]. Initialises the Cast framework lazily and only when Google
 * Play services is present (so Android TV / Play-less devices simply report cast unavailable),
 * and selects the physical LAN interface for the phone's Cast media bridge.
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
