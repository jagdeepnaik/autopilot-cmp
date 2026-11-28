package com.jagdeep.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What Compass's public `GET /api/app/:platform` says is the newest build for this platform. */
data class LatestBuild(val version: String, val build: Int?, val downloadUrl: String?)

/** A newer build than the one running, to offer in Settings → About. */
data class AppUpdate(val version: String, val build: Int, val downloadUrl: String)

/**
 * The update rule (diagnostics contract, "Update check"): offer [latest] only when both build numbers
 * are known and the server's is higher. The same build, an older one, nothing published or a missing
 * number all show nothing.
 */
fun newerUpdate(ownBuild: Int?, latest: LatestBuild?): AppUpdate? {
    val build = latest?.build ?: return null
    val own = ownBuild ?: return null
    val url = latest.downloadUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://127.0.0.1") } ?: return null
    if (latest.version.isBlank() || build <= own) return null
    return AppUpdate(latest.version.take(40), build, url)
}

/** The build number in a SemVer build identity such as `1.1.0+build.170.gfadd6e3`, or null. */
fun buildNumberOf(identity: String): Int? = Regex("\\+build\\.(\\d{1,6})(?:\\.|$)").find(identity)?.groupValues?.get(1)?.toIntOrNull()

/** Whether a newer build of the app is published. Never reports errors: a failed check shows nothing. */
interface UpdateCheck {
    val update: StateFlow<AppUpdate?>

    /** Asks Compass again (at start-up and when About opens); repeated calls within a few minutes are ignored. */
    fun check()
}

object NoUpdateCheck : UpdateCheck {
    override val update: StateFlow<AppUpdate?> = MutableStateFlow<AppUpdate?>(null).asStateFlow()
    override fun check() = Unit
}

/** Checks with [fetch] (null on 404 or any failure) and keeps the result for [update]. */
class UpdateChecker(
    private val scope: CoroutineScope,
    private val ownBuild: Int?,
    private val now: () -> Long,
    private val minIntervalMillis: Long = 5 * 60_000,
    private val fetch: suspend () -> LatestBuild?,
) : UpdateCheck {
    private val mutable = MutableStateFlow<AppUpdate?>(null)
    override val update: StateFlow<AppUpdate?> = mutable.asStateFlow()
    private var job: Job? = null
    private var checkedAt: Long? = null

    override fun check() {
        if (ownBuild == null || job?.isActive == true) return
        val at = now()
        checkedAt?.let { if (at - it < minIntervalMillis) return }
        checkedAt = at
        job = scope.launch {
            val latest = try { fetch() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            // A failed check keeps what an earlier one found rather than hiding a known update.
            if (latest != null) mutable.value = newerUpdate(ownBuild, latest)
        }
    }
}
