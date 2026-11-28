package com.jagdeep.home.data.cosmos

import com.jagdeep.home.domain.cosmos.CosmosAccountWire
import com.jagdeep.home.domain.cosmos.CosmosAccountsPage
import com.jagdeep.home.domain.cosmos.CosmosApi
import com.jagdeep.home.domain.cosmos.CosmosApiException
import com.jagdeep.home.domain.cosmos.CosmosBrowserLink
import com.jagdeep.home.domain.cosmos.CosmosCreateAccountRequest
import com.jagdeep.home.domain.cosmos.CosmosCreatedAccountResponse
import com.jagdeep.home.domain.cosmos.CosmosEntriesPage
import com.jagdeep.home.domain.cosmos.CosmosMachineWire
import com.jagdeep.home.domain.cosmos.CosmosMachinesPage
import com.jagdeep.home.domain.cosmos.CosmosUpdateAccountRequest
import com.jagdeep.home.domain.cosmos.CosmosSession
import com.jagdeep.home.domain.cosmos.CosmosSubmission
import com.jagdeep.home.domain.cosmos.CosmosSubmissionResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cosmos over HTTPS with Ktor: Q&A sync and run-log uploads. Requests go only to [baseUrl]; every one carries
 * `X-AutoPilot-App-Version`. Responses are mapped to [CosmosApiException]s without their bodies, so
 * nothing the server sends (or any token) reaches logs or the UI.
 */
class KtorCosmosApi(
    baseUrl: String,
    private val appVersion: String,
    engine: HttpClientEngine,
) : CosmosApi {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 30_000
        }
    }

    init { require(isAllowedBaseUrl(base)) { "Cosmos URL must use HTTPS" } }

    override suspend fun login(email: String, password: String, deviceName: String, machineId: String, platform: String): CosmosSession =
        call(HttpMethod.Post, listOf("auth", "app", "login"), body = encode(LoginRequest.serializer(), LoginRequest(email, password, deviceName.take(60), machineId, platform))) { decode(CosmosSession.serializer(), it) }

    override suspend fun changePassword(accessToken: String, currentPassword: String, newPassword: String): CosmosSession? =
        call(HttpMethod.Post, listOf("auth", "app", "change-password"), accessToken,
            body = encode(ChangePasswordRequest.serializer(), ChangePasswordRequest(currentPassword, newPassword))) { text ->
            // The contract doesn't fix the reply: a new session is used when Cosmos sends one.
            if (text.isBlank()) null else runCatching { decode(CosmosSession.serializer(), text) }.getOrNull()
        }

    override suspend fun browserLink(accessToken: String): CosmosBrowserLink =
        call(HttpMethod.Post, listOf("auth", "app", "browser-link"), accessToken, body = "{}") { decode(CosmosBrowserLink.serializer(), it) }
            // Only a link to this same Cosmos is ever opened.
            .also { if (!isSameSite(it.url)) throw CosmosApiException.ServerTrouble(200) }

    override suspend fun adminAccounts(accessToken: String, status: String?, query: String?, page: Int, pageSize: Int): CosmosAccountsPage =
        call(HttpMethod.Get, listOf("admin", "accounts"), accessToken, query = buildMap {
            status?.let { put("status", it) }
            query?.let { put("q", it) }
            put("page", page.coerceAtLeast(1).toString())
            put("pageSize", pageSize.coerceIn(1, 100).toString())
        }) { decode(CosmosAccountsPage.serializer(), it) }

    override suspend fun adminCreateAccount(accessToken: String, request: CosmosCreateAccountRequest): CosmosCreatedAccountResponse =
        call(HttpMethod.Post, listOf("admin", "accounts"), accessToken, body = encode(CosmosCreateAccountRequest.serializer(), request)) {
            decode(CosmosCreatedAccountResponse.serializer(), it)
        }

    override suspend fun adminUpdateAccount(accessToken: String, id: String, request: CosmosUpdateAccountRequest): CosmosAccountWire =
        call(HttpMethod.Patch, listOf("admin", "accounts", id), accessToken, body = encode(CosmosUpdateAccountRequest.serializer(), request)) {
            decode(AccountResponse.serializer(), it).account
        }

    override suspend fun adminResetPassword(accessToken: String, id: String): String =
        call(HttpMethod.Post, listOf("admin", "accounts", id, "reset-password"), accessToken, body = "{}") {
            decode(ResetPasswordResponse.serializer(), it).temporaryPassword
        }

    override suspend fun adminMachines(accessToken: String, status: String?, page: Int, pageSize: Int): CosmosMachinesPage =
        call(HttpMethod.Get, listOf("admin", "machines"), accessToken, query = buildMap {
            status?.let { put("status", it) }
            put("page", page.coerceAtLeast(1).toString())
            put("pageSize", pageSize.coerceIn(1, 100).toString())
        }) { decode(CosmosMachinesPage.serializer(), it) }

    override suspend fun adminApproveMachine(accessToken: String, id: String): CosmosMachineWire =
        call(HttpMethod.Post, listOf("admin", "machines", id, "approve"), accessToken, body = "{}") { decode(MachineResponse.serializer(), it).machine }

    override suspend fun adminRevokeMachine(accessToken: String, id: String): CosmosMachineWire =
        call(HttpMethod.Post, listOf("admin", "machines", id, "revoke"), accessToken, body = "{}") { decode(MachineResponse.serializer(), it).machine }

    /** True when [url] is on this API's own host and scheme (the browser link must never point elsewhere). */
    internal fun isSameSite(url: String): Boolean {
        val link = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val own = runCatching { java.net.URI(base) }.getOrNull() ?: return false
        return link.scheme.equals(own.scheme, ignoreCase = true) && link.host != null && link.host.equals(own.host, ignoreCase = true) &&
            link.port == own.port && link.userInfo == null
    }

    override suspend fun refresh(refreshToken: String): CosmosSession =
        call(HttpMethod.Post, listOf("auth", "app", "refresh"), body = encode(RefreshRequest.serializer(), RefreshRequest(refreshToken))) { decode(CosmosSession.serializer(), it) }

    override suspend fun logout(refreshToken: String) =
        call(HttpMethod.Post, listOf("auth", "app", "logout"), body = encode(RefreshRequest.serializer(), RefreshRequest(refreshToken))) { }

    override suspend fun entries(accessToken: String, bank: String, after: Long, limit: Int): CosmosEntriesPage =
        call(HttpMethod.Get, listOf("qa", "banks", bank, "entries"), accessToken, query = mapOf("after" to after.toString(), "limit" to limit.coerceIn(1, 1_000).toString())) {
            decode(CosmosEntriesPage.serializer(), it)
        }

    override suspend fun submit(accessToken: String, bank: String, submissions: List<CosmosSubmission>): List<CosmosSubmissionResult> {
        require(submissions.size in 1..100)
        return call(HttpMethod.Post, listOf("qa", "banks", bank, "submissions"), accessToken, body = encode(SubmissionsRequest.serializer(), SubmissionsRequest(submissions))) {
            decode(SubmissionsResponse.serializer(), it).results
        }
    }

    override suspend fun uploadRun(accessToken: String, body: String): String {
        val compressed = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.GZIPOutputStream(bytes).use { it.write(body.encodeToByteArray()) }
        }.toByteArray()
        return call(HttpMethod.Post, listOf("diagnostics", "runs"), accessToken, gzipBody = compressed) {
            decode(RunUploadResponse.serializer(), it).status
        }
    }

    /**
     * The public update check, `GET /api/app/:platform` (no sign-in); null when nothing is published
     * for [platform] (404). Other failures throw, and the caller shows nothing.
     */
    suspend fun latestBuild(platform: String): com.jagdeep.shared.update.LatestBuild? {
        require(platform in setOf("windows", "macos", "linux", "android"))
        return try {
            call(HttpMethod.Get, listOf("app", platform)) { decode(LatestBuildResponse.serializer(), it) }
                .let { com.jagdeep.shared.update.LatestBuild(it.version, it.build, it.downloadUrl) }
        } catch (_: CosmosApiException.NotFound) { null }
    }

    fun close() = client.close()

    private suspend fun <T> call(
        method: HttpMethod,
        path: List<String>,
        accessToken: String? = null,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        /** A JSON body already gzipped, sent with `Content-Encoding: gzip`. */
        gzipBody: ByteArray? = null,
        read: (String) -> T,
    ): T {
        val response: HttpResponse = try {
            client.request {
                this.method = method
                url(path, query)
                header(APP_VERSION_HEADER, appVersion)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                gzipBody?.let {
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(ByteArrayContent(it, ContentType.Application.Json))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw CosmosApiException.Offline() }
        val status = response.status.value
        when {
            status == 401 -> throw CosmosApiException.Unauthorized()
            // Only the contract's `code` is read from an error; its sentence and anything else are dropped.
            status == 403 -> throw CosmosApiException.Forbidden(errorCode(response))
            status == 409 -> throw CosmosApiException.Conflict(errorCode(response))
            status == 404 -> throw CosmosApiException.NotFound()
            status == 429 -> throw CosmosApiException.RateLimited(response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull())
            status >= 500 -> throw CosmosApiException.ServerTrouble(status)
            status !in 200..299 -> throw CosmosApiException.Rejected(status)
        }
        val text = try { response.bodyAsText() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { throw CosmosApiException.Offline() }
        return try { read(text) } catch (_: Exception) { throw CosmosApiException.ServerTrouble(status) }
    }

    private suspend fun errorCode(response: HttpResponse): String? = try {
        response.bodyAsText().takeIf { it.isNotBlank() }?.let { json.decodeFromString(ErrorResponse.serializer(), it).code }
            ?.takeIf { CODE.matches(it) }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    private fun HttpRequestBuilder.url(path: List<String>, query: Map<String, String>) {
        url.takeFrom(URLBuilder(base).apply {
            appendPathSegments(listOf("api") + path)
            query.forEach { (key, value) -> parameters.append(key, value) }
        })
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)
    private fun <T> decode(serializer: KSerializer<T>, text: String) = json.decodeFromString(serializer, text)

    @Serializable private data class LoginRequest(val email: String, val password: String, val deviceName: String, val machineId: String, val platform: String)
    @Serializable private data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
    @Serializable private data class ErrorResponse(val error: String? = null, val code: String? = null)
    @Serializable private data class AccountResponse(val account: CosmosAccountWire)
    @Serializable private data class ResetPasswordResponse(val temporaryPassword: String)
    @Serializable private data class MachineResponse(val machine: CosmosMachineWire)
    @Serializable private data class RefreshRequest(val refreshToken: String)
    @Serializable private data class SubmissionsRequest(val submissions: List<CosmosSubmission>)
    @Serializable private data class SubmissionsResponse(val results: List<CosmosSubmissionResult>)
    @Serializable private data class LatestBuildResponse(val platform: String? = null, val version: String, val build: Int? = null, val commit: String? = null, val releasedAt: String? = null, val downloadUrl: String? = null)
    @Serializable private data class RunUploadResponse(val runId: String? = null, val status: String)

    companion object {
        const val APP_VERSION_HEADER = "X-AutoPilot-App-Version"
        private val CODE = Regex("[A-Z][A-Z_]{1,63}")

        /** HTTPS only; plain HTTP is accepted for this computer's own loopback address (local testing). */
        fun isAllowedBaseUrl(url: String): Boolean {
            val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = parsed.host ?: return false
            return when (parsed.scheme?.lowercase()) {
                "https" -> parsed.userInfo == null
                "http" -> host == "127.0.0.1" || host == "localhost" || host == "[::1]"
                else -> false
            }
        }
    }
}

