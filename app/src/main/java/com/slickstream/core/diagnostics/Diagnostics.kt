package com.slickstream.core.diagnostics

import android.content.Context
import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.slickstream.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote diagnostics for real installs — so hard-to-reproduce bugs (a TV stream that goes black on a
 * mid-play failover, an RD source that never renders) surface as queryable reports instead of "it
 * happened once". Two sinks, both OPTIONAL and fail-safe (every call is wrapped so a missing/uninit
 * Firebase is a silent no-op):
 *  - Crashlytics: automatic crash/ANR capture + [breadcrumb] logs + custom keys, so a crash carries the
 *    last player state. Collection is OFF in debug builds (dev testing must not pollute the console).
 *  - Firestore `diagnostics/{installId}/events`: durable, queryable breadcrumbs for the non-crash player
 *    events we care about (failover, VLC fallback, black-frame). RELEASE builds only.
 *
 * PRIVACY: keyed by a random per-install UUID (NEVER the user's email). Callers must pass only
 * source TYPE / quality / error CODES — never the RD token, magnet URI, or exact title.
 */
@Singleton
class Diagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stable random id for this install — decoupled from any account, so reports can't be tied to a person. */
    private val installId: String by lazy {
        prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    private val crashlytics: FirebaseCrashlytics? by lazy {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
                setUserId(installId)
            }
        }.getOrNull()
    }

    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    /** A cheap breadcrumb — attaches to the NEXT crash report only. Safe to call often. */
    fun breadcrumb(message: String) {
        runCatching { crashlytics?.log(message) }
    }

    /** A notable event: breadcrumb + Crashlytics custom keys (for crash context) + a durable, queryable
     *  Firestore row aggregated across all RELEASE installs. Keep [data] free of secrets. */
    fun event(name: String, data: Map<String, String> = emptyMap()) {
        runCatching {
            crashlytics?.let { c ->
                c.log("evt:$name " + data.entries.joinToString(" ") { "${it.key}=${it.value}" })
                data.forEach { (k, v) -> c.setCustomKey("evt_$k", v) }
            }
        }
        if (BuildConfig.DEBUG) return   // don't spam the shared Firestore from dev builds
        scope.launch {
            runCatching {
                firestore?.collection("diagnostics")?.document(installId)
                    ?.collection("events")?.add(
                        buildMap<String, Any> {
                            put("event", name)
                            put("at", Timestamp.now())
                            put("versionName", BuildConfig.VERSION_NAME)
                            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                            put("sdk", Build.VERSION.SDK_INT)
                            data.forEach { (k, v) -> put(k, v) }
                        },
                    )
            }
        }
    }

    /** A recovered-but-notable error we want a stack + aggregation for (shows up as a Crashlytics
     *  non-fatal issue, grouped across installs). */
    fun nonFatal(t: Throwable, data: Map<String, String> = emptyMap()) {
        runCatching {
            crashlytics?.let { c ->
                data.forEach { (k, v) -> c.setCustomKey("nf_$k", v) }
                c.recordException(t)
            }
        }
    }

    private companion object {
        const val KEY_INSTALL_ID = "install_id"
    }
}
