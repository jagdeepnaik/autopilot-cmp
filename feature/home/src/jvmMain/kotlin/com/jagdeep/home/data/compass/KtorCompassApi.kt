package com.jagdeep.home.data.compass

import com.jagdeep.home.domain.compass.CompassApi
import com.jagdeep.home.domain.compass.CompassApiException
import com.jagdeep.home.domain.compass.CompassEntriesPage
import com.jagdeep.home.domain.compass.CompassSession
import com.jagdeep.home.domain.compass.CompassSubmission
import com.jagdeep.home.domain.compass.CompassSubmissionResult
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
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Compass over HTTPS with Ktor. Requests go only to [baseUrl]; every one carries
 * `X-AutoPilot-App-Version`. Responses are mapped to [CompassApiException]s without their bodies, so
 * nothing the server sends (or any token) reaches logs or the UI.
 */
class KtorCompassApi(
    baseUrl: String,
    private val appVersion: String,
    engine: HttpClientEngine,
) : CompassApi {
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

    init { require(isAllowedBaseUrl(base)) { "Compass URL must use HTTPS" } }

    override suspend fun login(email: String, password: String, deviceName: String): CompassSession =
        call(HttpMethod.Post, listOf("auth", "app", "login"), body = encode(LoginRequest.serializer(), LoginRequest(email, password, deviceName.take(60)))) { decode(CompassSession.serializer(), it) }

    override suspend fun refresh(refreshToken: String): CompassSession =
        call(HttpMethod.Post, listOf("auth", "app", "refresh"), body = encode(RefreshRequest.serializer(), RefreshRequest(refreshToken))) { decode(CompassSession.serializer(), it) }

    override suspend fun logout(refreshToken: String) =
        call(HttpMethod.Post, listOf("auth", "app", "logout"), body = encode(RefreshRequest.serializer(), RefreshRequest(refreshToken))) { }

    override suspend fun entries(accessToken: String, bank: String, after: Long, limit: Int): CompassEntriesPage =
        call(HttpMethod.Get, listOf("qa", "banks", bank, "entries"), accessToken, query = mapOf("after" to after.toString(), "limit" to limit.coerceIn(1, 1_000).toString())) {
            decode(CompassEntriesPage.serializer(), it)
        }

    override suspend fun submit(accessToken: String, bank: String, submissions: List<CompassSubmission>): List<CompassSubmissionResult> {
        require(submissions.size in 1..100)
        return call(HttpMethod.Post, listOf("qa", "banks", bank, "submissions"), accessToken, body = encode(SubmissionsRequest.serializer(), SubmissionsRequest(submissions))) {
            decode(SubmissionsResponse.serializer(), it).results
        }
    }

    fun close() = client.close()

    private suspend fun <T> call(
        method: HttpMethod,
        path: List<String>,
        accessToken: String? = null,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
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
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw CompassApiException.Offline() }
        val status = response.status.value
        when {
            status == 401 -> throw CompassApiException.Unauthorized()
            status == 404 -> throw CompassApiException.NotFound()
            status == 429 -> throw CompassApiException.RateLimited(response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull())
            status >= 500 -> throw CompassApiException.ServerTrouble(status)
            status !in 200..299 -> throw CompassApiException.Rejected(status)
        }
        val text = try { response.bodyAsText() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { throw CompassApiException.Offline() }
        return try { read(text) } catch (_: Exception) { throw CompassApiException.ServerTrouble(status) }
    }

    private fun HttpRequestBuilder.url(path: List<String>, query: Map<String, String>) {
        url.takeFrom(URLBuilder(base).apply {
            appendPathSegments(listOf("api") + path)
            query.forEach { (key, value) -> parameters.append(key, value) }
        })
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)
    private fun <T> decode(serializer: KSerializer<T>, text: String) = json.decodeFromString(serializer, text)

    @Serializable private data class LoginRequest(val email: String, val password: String, val deviceName: String)
    @Serializable private data class RefreshRequest(val refreshToken: String)
    @Serializable private data class SubmissionsRequest(val submissions: List<CompassSubmission>)
    @Serializable private data class SubmissionsResponse(val results: List<CompassSubmissionResult>)

    companion object {
        const val APP_VERSION_HEADER = "X-AutoPilot-App-Version"

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

