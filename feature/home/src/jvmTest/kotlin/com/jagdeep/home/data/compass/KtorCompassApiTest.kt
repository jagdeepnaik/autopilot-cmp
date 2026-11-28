package com.jagdeep.home.data.compass

import com.jagdeep.home.domain.compass.CompassAnswer
import com.jagdeep.home.domain.compass.CompassApiException
import com.jagdeep.home.domain.compass.CompassSubmission
import com.jagdeep.home.domain.compass.CompassOption
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class KtorCompassApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorCompassApi("https://compass.test", "1.1.0+build.7.gabc1234", MockEngine { request -> requests += request; handler(request) })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyText() = (body as TextContent).text

    private val session = """{"accessToken":"test-access","accessTokenExpiresIn":900,"refreshToken":"test-refresh-2","refreshTokenExpiresAt":"2027-02-26T10:00:00.000Z","user":{"id":"u_1","name":"Tester","role":"seafarer"}}"""

    @Test fun given_login_when_called_then_it_posts_the_contract_body_with_the_app_version() = runBlocking {
        val result = api { json(session) }.login("person@example.com", "test-password", "AutoPilot on macOS")
        assertEquals("test-refresh-2", result.refreshToken)
        assertEquals("Tester", result.user.name)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://compass.test/api/auth/app/login", request.url.toString())
        assertEquals("1.1.0+build.7.gabc1234", request.headers[KtorCompassApi.APP_VERSION_HEADER])
        assertEquals(null, request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(request.bodyText()).jsonObject
        assertEquals(setOf("email", "password", "deviceName"), body.keys)
    }

    @Test fun given_entries_when_called_then_it_sends_the_bearer_token_and_cursor_and_reads_the_page() = runBlocking {
        val page = """{"entries":[{"id":"e_1","bank":"smy","bankName":"sagarmeinyog-ver-1.0.json","module":"Module 3","question":"Q?","questionKey":"${"a".repeat(64)}",
            "options":[{"position":1,"text":"Two"},{"position":2,"text":"Three"}],
            "answers":[{"text":"Three","answerKey":"${"b".repeat(64)}"}],"source":"bundled","status":"approved","revision":1042,"updatedAt":"2026-11-28T09:12:00.000Z","extra":"ignored"}],
            "nextAfter":1042,"hasMore":false}"""
        val result = api { json(page) }.entries("test-access", "smy", 1000, 500)
        assertEquals(1042, result.nextAfter)
        assertEquals("Three", result.entries.single().answers.single().text)
        assertEquals("sagarmeinyog-ver-1.0.json", result.entries.single().bankName)
        val request = requests.single()
        assertEquals("https://compass.test/api/qa/banks/smy/entries?after=1000&limit=500", request.url.toString())
        assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
    }

    @Test fun given_submissions_when_sent_then_the_body_has_no_unknown_fields() = runBlocking {
        val submission = CompassSubmission("6f1d2a7e-0000-4000-8000-000000000000", "sagarmeinyog-ver-1.0.json", "Module 3", "Q?", "a".repeat(64),
            listOf(CompassOption(1, "Two"), CompassOption(2, "Three")),
            CompassAnswer("Three", "b".repeat(64)), "2026-11-28T08:55:10Z", "2026-11-28T08:56:00Z")
        val results = api { json("""{"results":[{"clientId":"6f1d2a7e-0000-4000-8000-000000000000","status":"conflict"}]}""") }
            .submit("test-access", "smy", listOf(submission))
        assertEquals("conflict", results.single().status)
        val body = Json.parseToJsonElement(requests.single().bodyText()).jsonObject
        val sent = body["submissions"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("clientId", "bankName", "module", "question", "questionKey", "options", "answer", "learnedAt", "reviewedAt"), sent.keys)
        assertEquals(setOf("position", "text"), sent["options"]!!.jsonArray.first().jsonObject.keys)
        assertEquals(setOf("text", "answerKey"), sent["answer"]!!.jsonObject.keys)
    }

    @Test fun given_error_statuses_when_called_then_they_map_to_safe_exceptions() = runBlocking {
        suspend fun failureFor(status: HttpStatusCode, headers: Map<String, String> = emptyMap()): CompassApiException =
            assertFailsWith<CompassApiException> {
                KtorCompassApi("https://compass.test", "v", MockEngine {
                    respond("""{"error":"Something a person can read about test-refresh-2"}""", status,
                        io.ktor.http.Headers.build { headers.forEach { (k, v) -> append(k, v) } })
                }).refresh("test-refresh-1")
            }
        assertTrue(failureFor(HttpStatusCode.Unauthorized) is CompassApiException.Unauthorized)
        assertTrue(failureFor(HttpStatusCode.NotFound) is CompassApiException.NotFound)
        assertTrue(failureFor(HttpStatusCode.BadRequest) is CompassApiException.Rejected)
        assertTrue(failureFor(HttpStatusCode.ServiceUnavailable) is CompassApiException.ServerTrouble)
        val limited = failureFor(HttpStatusCode.TooManyRequests, mapOf(HttpHeaders.RetryAfter to "42"))
        assertEquals(42L, (limited as CompassApiException.RateLimited).retryAfterSeconds)
        // No response body or token ever reaches the exception text.
        assertFalse(limited.message.orEmpty().contains("test-refresh"))
        assertFalse(failureFor(HttpStatusCode.Unauthorized).message.orEmpty().contains("test-refresh"))
    }

    @Test fun given_the_network_fails_when_called_then_it_reports_offline() = runBlocking {
        val failing = KtorCompassApi("https://compass.test", "v", MockEngine { throw java.io.IOException("connection reset for test-refresh-1") })
        val error = assertFailsWith<CompassApiException.Offline> { failing.refresh("test-refresh-1") }
        assertFalse(error.message.orEmpty().contains("test-refresh"))
    }

    @Test fun given_a_base_url_when_checked_then_only_https_or_loopback_http_is_allowed() {
        assertTrue(KtorCompassApi.isAllowedBaseUrl("https://compass.naikio.in"))
        assertTrue(KtorCompassApi.isAllowedBaseUrl("http://127.0.0.1:4000"))
        assertFalse(KtorCompassApi.isAllowedBaseUrl("http://compass.naikio.in"))
        assertFalse(KtorCompassApi.isAllowedBaseUrl("https://user:pass@compass.naikio.in"))
        assertFalse(KtorCompassApi.isAllowedBaseUrl("ftp://compass.naikio.in"))
        assertFailsWith<IllegalArgumentException> { KtorCompassApi("http://example.com", "v", MockEngine { respond("") }) }
    }

    @Test fun given_a_run_log_when_uploaded_then_it_posts_gzipped_json_with_the_bearer_token() = runBlocking {
        val body = """{"runId":"r1","header":{"type":"run.start","runId":"r1"},"events":[],"end":null,"truncated":false}"""
        val status = api { json("""{"runId":"r1","status":"stored"}""") }.uploadRun("test-access", body)
        assertEquals("stored", status)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://compass.test/api/diagnostics/runs", request.url.toString())
        assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
        assertEquals("1.1.0+build.7.gabc1234", request.headers[KtorCompassApi.APP_VERSION_HEADER])
        val content = request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent
        assertEquals("gzip", request.headers[HttpHeaders.ContentEncoding] ?: content.headers[HttpHeaders.ContentEncoding])
        val inflated = java.util.zip.GZIPInputStream(content.bytes().inputStream()).readBytes().decodeToString()
        assertEquals(body, inflated)
    }

    @Test fun given_a_too_large_or_malformed_run_when_uploaded_then_it_is_rejected() = runBlocking {
        val tooLarge = assertFailsWith<CompassApiException.Rejected> { api { json("""{"error":"Too large"}""", HttpStatusCode.PayloadTooLarge) }.uploadRun("a", "{}") }
        assertEquals(413, tooLarge.status)
        val malformed = assertFailsWith<CompassApiException.Rejected> { api { json("""{"error":"Bad"}""", HttpStatusCode.BadRequest) }.uploadRun("a", "{}") }
        assertEquals(400, malformed.status)
    }

    @Test fun given_the_update_check_when_called_then_it_asks_without_a_token_and_404_means_nothing_published() = runBlocking {
        val latest = api { json("""{"platform":"macos","version":"1.1.1","build":171,"commit":"abc1234","releasedAt":"2026-11-28T12:00:00.000Z","downloadUrl":"https://compass.test/downloads"}""") }.latestBuild("macos")
        assertEquals(com.jagdeep.shared.update.LatestBuild("1.1.1", 171, "https://compass.test/downloads"), latest)
        assertEquals("https://compass.test/api/app/macos", requests.last().url.toString())
        assertEquals(null, requests.last().headers[HttpHeaders.Authorization])
        assertEquals(null, api { json("""{"error":"Not found"}""", HttpStatusCode.NotFound) }.latestBuild("linux"))
    }
}
