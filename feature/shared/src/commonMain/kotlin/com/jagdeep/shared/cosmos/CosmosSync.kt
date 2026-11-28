package com.jagdeep.shared.cosmos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opt-in Q&A sync with Cosmos: the routine Q&A banks, plus sanitised routine run logs for
 * diagnosing stuck routines. Course automation owns the engine;
 * Settings shows its state and signs in and out. Holds no credentials: tokens never leave the engine.
 */
interface CosmosSync {
    val state: StateFlow<CosmosSyncState>

    /**
     * The password (and [totpCode], the admin's authenticator or recovery code) is used for this one request
     * and never kept. Send [totpCode] only after a [CosmosSignInResult.TotpRequired] or [CosmosSignInResult.TotpInvalid].
     */
    suspend fun signIn(email: String, password: String, totpCode: String? = null): CosmosSignInResult

    /** Ends the session on Cosmos when reachable, and always forgets it on this computer. */
    suspend fun signOut()

    /** Starts a sync now unless one is running. Does nothing while signed out. */
    fun syncNow()

    /** A confirmed answer was saved; uploads it shortly (several saves share one sync). */
    fun answersChanged()

    /**
     * Chooses a new password while the account must change its temporary one. Neither password is kept;
     * the new one must have at least [MIN_PASSWORD_LENGTH] characters.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): CosmosPasswordChangeResult = CosmosPasswordChangeResult.Unavailable

    /**
     * A one-time link that signs the default browser on this computer in to Cosmos (valid about a minute).
     * The link carries a secret: open it, never log or show it.
     */
    suspend fun browserLink(): CosmosBrowserLinkResult = CosmosBrowserLinkResult.Failed(CosmosProblem.Unavailable)

    /** Account and machine administration; works only while the signed-in account is the admin. */
    val admin: CosmosAdmin get() = NoCosmosAdmin

    companion object {
        /** The contract's shortest allowed password. */
        const val MIN_PASSWORD_LENGTH = 12

        /** Longest authenticator or recovery code the app accepts (a TOTP code is 6 digits). */
        const val MAX_TOTP_CODE_LENGTH = 20

        /**
         * The code as sent: spaces dropped, letters, digits and dashes only, at most [MAX_TOTP_CODE_LENGTH]
         * characters; null when nothing usable is left.
         */
        fun normalizedTotpCode(code: String?): String? = code?.filter { !it.isWhitespace() }
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TOTP_CODE_LENGTH && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' } }
    }
}

/** [role] is Cosmos's role for the account; `admin` shows the admin pages. */
data class CosmosAccount(val name: String, val role: String? = null) {
    val isAdmin: Boolean get() = role == ADMIN_ROLE

    companion object { const val ADMIN_ROLE = "admin" }
}

enum class CosmosSyncOutcome { Synced, Offline, ServerTrouble, RateLimited, SignInNeeded, StorageProblem }

/** Why Cosmos ended a session, so Settings can say what happened. */
enum class CosmosSessionEnd { Expired, MachineRevoked, AccountDisabled }

data class CosmosSyncState(
    /** False where sync isn't offered (mobile, or no secure storage on this desktop). */
    val available: Boolean = false,
    val account: CosmosAccount? = null,
    val syncing: Boolean = false,
    val lastSyncAtMillis: Long? = null,
    val lastOutcome: CosmosSyncOutcome? = null,
    /** Approved answers downloaded and answers sent in the last successful sync. */
    val lastDownloaded: Int = 0,
    val lastSent: Int = 0,
    val pendingUploads: Int = 0,
    /** Finished routine run logs on this computer that Cosmos hasn't received yet. */
    val runLogsWaiting: Int = 0,
    /** Cosmos ended the session (expired or signed out elsewhere); the user needs to sign in again. */
    val signInExpired: Boolean = false,
    /** Why the session ended, when Cosmos said; null for an ordinary expiry or while signed in. */
    val sessionEnd: CosmosSessionEnd? = null,
    /** Signed in with a temporary password: nothing syncs until a new password is chosen. */
    val passwordChangeRequired: Boolean = false,
)

enum class CosmosSignInResult {
    SignedIn, WrongEmailOrPassword, Offline, RateLimited, ServerTrouble, SecureStorageUnavailable, Unavailable,
    /** Cosmos recorded this computer; the admin has to approve it before it can sign in. */
    MachinePending,
    /** The admin revoked this computer for the account. */
    MachineRevoked,
    AccountDisabled,
    /** This computer's identifier couldn't be read, so Cosmos can't approve it. */
    ComputerNotIdentified,
    /** The admin's password was right; Cosmos wants the code from the authenticator app (send the same sign-in again with it). */
    TotpRequired,
    /** The authenticator or recovery code was wrong or already used. */
    TotpInvalid,
    /** The admin hasn't set up two-step sign-in on the server yet (`npm run admin:totp`). */
    TotpSetupRequired,
}

enum class CosmosPasswordChangeResult { Changed, TooShort, WrongCurrentPassword, Offline, RateLimited, ServerTrouble, SignInNeeded, Unavailable }

/** A failure of an account request, in terms Settings can explain. Carries no server text or token. */
enum class CosmosProblem { Offline, RateLimited, ServerTrouble, SignInNeeded, NotAllowed, NotFound, Conflict, Invalid, Unavailable }

sealed interface CosmosBrowserLinkResult {
    /** [url] holds a one-time secret: open it and drop it. [toString] never prints it. */
    class Link(val url: String) : CosmosBrowserLinkResult { override fun toString() = "Link(…)" }
    data class Failed(val problem: CosmosProblem) : CosmosBrowserLinkResult
}

object NoCosmosSync : CosmosSync {
    private val unavailable = MutableStateFlow(CosmosSyncState()).asStateFlow()
    override val state: StateFlow<CosmosSyncState> = unavailable
    override suspend fun signIn(email: String, password: String, totpCode: String?) = CosmosSignInResult.Unavailable
    override suspend fun signOut() = Unit
    override fun syncNow() = Unit
    override fun answersChanged() = Unit
}
