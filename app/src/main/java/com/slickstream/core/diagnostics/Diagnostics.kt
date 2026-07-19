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

    /** In-memory ring of recent breadcrumbs (with uptime stamps) so a captured crash carries the exact
     *  trail of what ran just before it — the "flight recorder" tape. */
    private val trail = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** A cheap breadcrumb — into Crashlytics AND the local trail (so the flight recorder can attach it
     *  to a fatal even when Crashlytics can't, e.g. a native abort). Safe to call often. */
    fun breadcrumb(message: String) {
        val stamped = "+${android.os.SystemClock.elapsedRealtime()}ms $message"
        trail.addLast(stamped)
        while (trail.size > TRAIL_MAX) trail.pollFirst()
        runCatching { crashlytics?.log(message) }
    }

    /**
     * FLIGHT RECORDER: chain the process's uncaught-exception handler so a FATAL crash is written to a
     * local file SYNCHRONOUSLY (before the process dies), together with the breadcrumb trail. Uploaded
     * to Firestore on the NEXT launch by [flushPendingCrash]. This captures crashes our try/catches
     * miss (e.g. a WebView Error, a Compose/main-thread throw) and — unlike Crashlytics, which also
     * uploads next-launch — lands the FULL stack in a place we can read directly. Call once at launch.
     */
    fun installCrashRecorder() {
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    val body = buildString {
                        append("thread=").append(thread.name).append('\n')
                        append("version=").append(BuildConfig.VERSION_NAME).append('\n')
                        append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                            .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
                        append("message=").append(throwable.toString()).append('\n')
                        append("--- trail (most recent last) ---\n")
                        trail.forEach { append(it).append('\n') }
                        append("--- stack ---\n").append(sw.toString())
                    }
                    java.io.File(context.filesDir, PENDING_CRASH_FILE).writeText(body)
                }
                // Always chain to the prior handler (Crashlytics) so its own capture still runs.
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** Upload a crash captured by the flight recorder on the PREVIOUS run, then KEEP it as the "last
     *  crash" so the in-app viewer can show it even when Firebase can't upload (a box with no Play
     *  Services). Runs even in debug so on-device testing surfaces the stack too. */
    fun flushPendingCrash() {
        scope.launch {
            runCatching {
                val f = java.io.File(context.filesDir, PENDING_CRASH_FILE)
                if (!f.exists()) return@launch
                val body = f.readText()
                // Keep a copy for the on-screen viewer BEFORE any upload (upload may fail/no-op).
                runCatching { java.io.File(context.filesDir, LAST_CRASH_FILE).writeText(body) }
                f.delete()
                // Best-effort upload (no-ops without Play Services / Firebase).
                runCatching { crashlytics?.recordException(RecordedCrash(body.lineSequence().firstOrNull { it.startsWith("message=") } ?: "recorded crash")) }
                firestore?.collection("diagnostics")?.document(installId)
                    ?.collection("crashes")?.add(
                        mapOf(
                            "at" to Timestamp.now(),
                            "versionName" to BuildConfig.VERSION_NAME,
                            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                            "sdk" to Build.VERSION.SDK_INT,
                            "report" to body.take(9000),   // Firestore field cap headroom
                        ),
                    )
            }
        }
    }

    /** The most recent captured crash report (for the on-screen viewer), or null if none. */
    fun lastCrashReport(): String? =
        runCatching { java.io.File(context.filesDir, LAST_CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clearLastCrash() {
        runCatching { java.io.File(context.filesDir, LAST_CRASH_FILE).delete() }
    }

    /** Human-readable environment snapshot for the viewer — the things that actually determine whether
     *  live sports and diagnostics work on THIS box (WebView + Play Services + Firebase). */
    fun environmentReport(): String = buildString {
        append("App: ").append(BuildConfig.VERSION_NAME)
            .append(if (BuildConfig.DEBUG) " (debug)" else "").append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        val webview = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
            else "assumed present (pre-O)"
        }.getOrNull()
        append("WebView: ").append(webview ?: "NOT AVAILABLE (live sports can't play)").append('\n')
        val play = runCatching {
            context.packageManager.getPackageInfo("com.google.android.gms", 0) != null
        }.getOrDefault(false)
        append("Play Services: ").append(if (play) "present" else "NOT PRESENT (Firebase diagnostics won't upload)").append('\n')
        append("Firebase: ").append(if (firestore != null) "initialized" else "not initialized").append('\n')
        append("Install id: ").append(installId).append('\n')
    }

    private class RecordedCrash(msg: String) : Exception(msg)

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

    /** Fire ONE verifiable report per app version per install — a Firestore `diagnostics_online` event
     *  AND a Crashlytics non-fatal — so the pipeline can be confirmed live WITHOUT waiting for a real
     *  crash or player event. Once per versionName per device = negligible noise; each update re-proves
     *  it. No-ops in debug (collection off + Firestore skipped). */
    fun heartbeatOncePerVersion() {
        val current = BuildConfig.VERSION_NAME
        if (prefs.getString(KEY_HEARTBEAT_VERSION, null) == current) return
        prefs.edit().putString(KEY_HEARTBEAT_VERSION, current).apply()
        event("diagnostics_online", mapOf("via" to "heartbeat"))
        nonFatal(DiagnosticsHeartbeat(current), mapOf("kind" to "heartbeat"))
    }

    /** Non-fatal marker for [heartbeatOncePerVersion] — a distinct type so it groups on its own in the
     *  Crashlytics console (clearly a self-test, not a real error). */
    private class DiagnosticsHeartbeat(version: String) : Exception("Diagnostics online — v$version")

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
        const val KEY_HEARTBEAT_VERSION = "heartbeat_version"
        const val PENDING_CRASH_FILE = "pending_crash.txt"
        const val LAST_CRASH_FILE = "last_crash.txt"
        const val TRAIL_MAX = 50
    }
}
