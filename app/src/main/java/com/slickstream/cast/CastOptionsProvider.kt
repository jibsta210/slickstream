package com.slickstream.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Tells the Cast framework which receiver app to use. We use Google's Default Media Receiver,
 * which plays HTTP-streamed MP4/WebM (and some MKV) without us hosting a custom receiver.
 *
 * Referenced by name from AndroidManifest via the OPTIONS_PROVIDER_CLASS_NAME meta-data.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
