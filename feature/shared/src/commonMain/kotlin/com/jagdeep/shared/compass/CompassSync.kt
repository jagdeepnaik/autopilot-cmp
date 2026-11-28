package com.jagdeep.shared.compass

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opt-in Q&A sync with Compass: the routine Q&A banks, plus sanitised routine run logs for
 * diagnosing stuck routines. Course automation owns the engine;
 * Settings shows its state and signs in and out. Holds no credentials: tokens never leave the engine.
 */
interface CompassSync {
    val state: StateFlow<CompassSyncState>

    /** The password is used for this one request and never kept. */
    suspend fun signIn(email: String, password: String): CompassSignInResult

    /** Ends the session on Compass when reachable, and always forgets it on this computer. */
    suspend fun signOut()

    /** Starts a sync now unless one is running. Does nothing while signed out. */
    fun syncNow()

    /** A confirmed answer was saved; uploads it shortly (several saves share one sync). */
    fun answersChanged()
}

data class CompassAccount(val name: String)

enum class CompassSyncOutcome { Synced, Offline, ServerTrouble, RateLimited, SignInNeeded, StorageProblem }

data class CompassSyncState(
    /** False where sync isn't offered (mobile, or no secure storage on this desktop). */
    val available: Boolean = false,
    val account: CompassAccount? = null,
    val syncing: Boolean = false,
    val lastSyncAtMillis: Long? = null,
    val lastOutcome: CompassSyncOutcome? = null,
    /** Approved answers downloaded and answers sent in the last successful sync. */
    val lastDownloaded: Int = 0,
    val lastSent: Int = 0,
    val pendingUploads: Int = 0,
    /** Finished routine run logs on this computer that Compass hasn't received yet. */
    val runLogsWaiting: Int = 0,
    /** Compass ended the session (expired or signed out elsewhere); the user needs to sign in again. */
    val signInExpired: Boolean = false,
)

enum class CompassSignInResult { SignedIn, WrongEmailOrPassword, Offline, RateLimited, ServerTrouble, SecureStorageUnavailable, Unavailable }

object NoCompassSync : CompassSync {
    private val unavailable = MutableStateFlow(CompassSyncState()).asStateFlow()
    override val state: StateFlow<CompassSyncState> = unavailable
    override suspend fun signIn(email: String, password: String) = CompassSignInResult.Unavailable
    override suspend fun signOut() = Unit
    override fun syncNow() = Unit
    override fun answersChanged() = Unit
}
