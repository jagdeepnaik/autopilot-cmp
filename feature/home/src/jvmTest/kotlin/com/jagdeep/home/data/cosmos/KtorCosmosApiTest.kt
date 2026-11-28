package com.jagdeep.home.data.cosmos

import com.jagdeep.home.domain.cosmos.CosmosAnswer
import com.jagdeep.home.domain.cosmos.CosmosApiException
import com.jagdeep.home.domain.cosmos.CosmosSubmission
import com.jagdeep.home.domain.cosmos.CosmosOption
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

class KtorCosmosApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorCosmosApi("https://cosmos.test", "1.1.0+build.7.gabc1234", MockEngine { request -> requests += request; handler(request) })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyText() = (body as TextContent).text

    private val session = """{"accessToken":"test-access","accessTokenExpiresIn":900,"refreshToken":"test-refresh-2","refreshTokenExpiresAt":"2027-02-26T10:00:00.000Z","user":{"id":"u_1","name":"Tester","role":"seafarer"}}"""

    @Test fun given_login_when_called_then_it_posts_the_contract_body_with_the_app_version() = runBlocking {
        val result = api { json(session) }.login("person@example.com", "test-password", "AutoPilot on macOS", "c".repeat(64), "macos")
        assertEquals("test-refresh-2", result.refreshToken)
        assertEquals("Tester", result.user.name)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://cosmos.test/api/auth/app/login", request.url.toString())
        assertEquals("1.1.0+build.7.gabc1234", request.headers[KtorCosmosApi.APP_VERSION_HEADER])
        assertEquals(null, request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(request.bodyText()).jsonObject
        assertEquals(setOf("email", "password", "deviceName", "machineId", "platform"), body.keys)
        assertEquals("c".repeat(64), body["machineId"].toString().trim('"'))
        assertEquals("macos", body["platform"].toString().trim('"'))
    }

    @Test fun given_an_approved_machine_when_logging_in_then_the_machine_and_password_flag_are_read() = runBlocking {
        val reply = session.dropLast(1) + ""","machine":{"id":"m_1","status":"approved"},"mustChangePassword":true}"""
        val result = api { json(reply) }.login("person@example.com", "test-password", "AutoPilot on macOS", "c".repeat(64), "macos")
        assertEquals("m_1", result.machine?.id)
        assertTrue(result.mustChangePassword)
    }

    @Test fun given_a_403_with_a_code_then_only_the_code_is_kept() = runBlocking {
        val error = assertFailsWith<CosmosApiException.Forbidden> {
            api { json("""{"error":"This computer is waiting for the admin's approval.","code":"MACHINE_PENDING"}""", HttpStatusCode.Forbidden) }
                .login("person@example.com", "test-password", "AutoPilot on macOS", "c".repeat(64), "macos")
        }
        assertEquals("MACHINE_PENDING", error.code)
        assertFalse(error.message.orEmpty().contains("waiting"))
        val garbled = assertFailsWith<CosmosApiException.Forbidden> { api { json("not json", HttpStatusCode.Forbidden) }.browserLink("test-access") }
        assertEquals(null, garbled.code)
    }

    @Test fun given_change_password_when_called_then_it_posts_both_passwords_with_the_token() = runBlocking {
        val result = api { respond("", HttpStatusCode.NoContent) }.changePassword("test-access", "temporary-one", "a-new-long-password")
        assertEquals(null, result)
        val request = requests.single()
        assertEquals("https://cosmos.test/api/auth/app/change-password", request.url.toString())
        assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
        assertEquals(setOf("currentPassword", "newPassword"), Json.parseToJsonElement(request.bodyText()).jsonObject.keys)
        val renewed = api { json(session) }.changePassword("test-access", "temporary-one", "a-new-long-password")
        assertEquals("test-refresh-2", renewed?.refreshToken)
    }

    @Test fun given_a_browser_link_on_the_same_site_then_it_is_returned_and_another_site_is_refused() = runBlocking {
        val link = api { json("""{"url":"https://cosmos.test/link#secret-token","expiresIn":60}""") }.browserLink("test-access")
        assertEquals("https://cosmos.test/link#secret-token", link.url)
        assertFalse(link.toString().contains("secret-token"))
        assertEquals("https://cosmos.test/api/auth/app/browser-link", requests.last().url.toString())
        assertEquals(HttpMethod.Post, requests.last().method)
        assertFailsWith<CosmosApiException.ServerTrouble> { api { json("""{"url":"https://elsewhere.test/link#t","expiresIn":60}""") }.browserLink("test-access") }
        assertFailsWith<CosmosApiException.ServerTrouble> { api { json("""{"url":"http://cosmos.test/link#t","expiresIn":60}""") }.browserLink("test-access") }
        Unit
    }

    @Test fun given_admin_routes_when_called_then_paths_queries_and_bodies_follow_the_contract() = runBlocking {
        val account = """{"id":"a_1","name":"Asha","email":"asha@example.com","role":"seafarer","status":"active","machineLimit":2,"approvedMachines":1,"pendingMachines":0,"mustChangePassword":true,"createdAt":"2026-11-28T10:00:00.000Z","lastSignInAt":null}"""
        val machine = """{"id":"m_1","machineId":"${"d".repeat(64)}","label":"AutoPilot on Windows","platform":"windows","status":"pending","firstSeenAt":"2026-11-28T10:00:00.000Z","lastSeenAt":"2026-11-28T10:00:00.000Z","approvedAt":null,"account":{"id":"a_1","name":"Asha","email":"asha@example.com"}}"""
        val api = api { request ->
            when (request.url.encodedPath) {
                "/api/admin/accounts" -> if (request.method == HttpMethod.Post) json("""{"account":$account,"temporaryPassword":"temp-pass-123"}""", HttpStatusCode.Created)
                    else json("""{"accounts":[$account],"page":1,"pageSize":50,"total":1}""")
                "/api/admin/accounts/a_1" -> json("""{"account":$account}""")
                "/api/admin/accounts/a_1/reset-password" -> json("""{"temporaryPassword":"temp-pass-456"}""")
                "/api/admin/machines" -> json("""{"machines":[$machine],"page":1,"pageSize":50,"total":1}""")
                "/api/admin/machines/m_1/approve" -> json("""{"error":"The account has reached its machine limit."}""", HttpStatusCode.Conflict)
                else -> json("""{"machine":$machine}""")
            }
        }
        assertEquals("Asha", api.adminAccounts("test-access", "active", "asha", 1, 50).accounts.single().name)
        assertEquals("https://cosmos.test/api/admin/accounts?status=active&q=asha&page=1&pageSize=50", requests.last().url.toString())
        val created = api.adminCreateAccount("test-access", com.jagdeep.home.domain.cosmos.CosmosCreateAccountRequest("Asha", "asha@example.com", 2))
        assertEquals("temp-pass-123", created.temporaryPassword)
        assertFalse(created.toString().contains("temp-pass-123"))
        assertEquals(setOf("name", "email", "machineLimit"), Json.parseToJsonElement(requests.last().bodyText()).jsonObject.keys)
        api.adminUpdateAccount("test-access", "a_1", com.jagdeep.home.domain.cosmos.CosmosUpdateAccountRequest(status = "disabled"))
        assertEquals(HttpMethod.Patch, requests.last().method)
        assertEquals(setOf("status"), Json.parseToJsonElement(requests.last().bodyText()).jsonObject.keys)
        assertEquals("temp-pass-456", api.adminResetPassword("test-access", "a_1"))
        val machines = api.adminMachines("test-access", "pending", 1, 50)
        assertEquals("https://cosmos.test/api/admin/machines?status=pending&page=1&pageSize=50", requests.last().url.toString())
        assertEquals("Asha", machines.machines.single().account?.name)
        assertFailsWith<CosmosApiException.Conflict> { api.adminApproveMachine("test-access", "m_1") }
        assertEquals("m_1", api.adminRevokeMachine("test-access", "m_1").id)
        assertEquals("https://cosmos.test/api/admin/machines/m_1/revoke", requests.last().url.toString())
    }

    @Test fun given_hostile_urls_then_only_this_site_counts_as_the_same_site() {
        val api = KtorCosmosApi("https://cosmos.test", "v", MockEngine { respond("") })
        assertTrue(api.isSameSite("https://cosmos.test/link#t"))
        assertTrue(api.isSameSite("https://COSMOS.test/link#t"))
        assertFalse(api.isSameSite("https://cosmos.test.evil.example/link#t"))
        assertFalse(api.isSameSite("https://user@cosmos.test/link#t"))
        assertFalse(api.isSameSite("https://cosmos.test:8443/link#t"))
        assertFalse(api.isSameSite("javascript:alert(1)"))
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
        assertEquals("https://cosmos.test/api/qa/banks/smy/entries?after=1000&limit=500", request.url.toString())
        assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
    }

    @Test fun given_submissions_when_sent_then_the_body_has_no_unknown_fields() = runBlocking {
        val submission = CosmosSubmission("6f1d2a7e-0000-4000-8000-000000000000", "sagarmeinyog-ver-1.0.json", "Module 3", "Q?", "a".repeat(64),
            listOf(CosmosOption(1, "Two"), CosmosOption(2, "Three")),
            CosmosAnswer("Three", "b".repeat(64)), "2026-11-28T08:55:10Z", "2026-11-28T08:56:00Z")
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
        suspend fun failureFor(status: HttpStatusCode, headers: Map<String, String> = emptyMap()): CosmosApiException =
            assertFailsWith<CosmosApiException> {
                KtorCosmosApi("https://cosmos.test", "v", MockEngine {
                    respond("""{"error":"Something a person can read about test-refresh-2"}""", status,
                        io.ktor.http.Headers.build { headers.forEach { (k, v) -> append(k, v) } })
                }).refresh("test-refresh-1")
            }
        assertTrue(failureFor(HttpStatusCode.Unauthorized) is CosmosApiException.Unauthorized)
        assertTrue(failureFor(HttpStatusCode.NotFound) is CosmosApiException.NotFound)
        assertTrue(failureFor(HttpStatusCode.BadRequest) is CosmosApiException.Rejected)
        assertTrue(failureFor(HttpStatusCode.ServiceUnavailable) is CosmosApiException.ServerTrouble)
        val limited = failureFor(HttpStatusCode.TooManyRequests, mapOf(HttpHeaders.RetryAfter to "42"))
        assertEquals(42L, (limited as CosmosApiException.RateLimited).retryAfterSeconds)
        // No response body or token ever reaches the exception text.
        assertFalse(limited.message.orEmpty().contains("test-refresh"))
        assertFalse(failureFor(HttpStatusCode.Unauthorized).message.orEmpty().contains("test-refresh"))
    }

    @Test fun given_the_network_fails_when_called_then_it_reports_offline() = runBlocking {
        val failing = KtorCosmosApi("https://cosmos.test", "v", MockEngine { throw java.io.IOException("connection reset for test-refresh-1") })
        val error = assertFailsWith<CosmosApiException.Offline> { failing.refresh("test-refresh-1") }
        assertFalse(error.message.orEmpty().contains("test-refresh"))
    }

    @Test fun given_a_base_url_when_checked_then_only_https_or_loopback_http_is_allowed() {
        assertTrue(KtorCosmosApi.isAllowedBaseUrl("https://cosmos.naikio.in"))
        assertTrue(KtorCosmosApi.isAllowedBaseUrl("http://127.0.0.1:4000"))
        assertFalse(KtorCosmosApi.isAllowedBaseUrl("http://cosmos.naikio.in"))
        assertFalse(KtorCosmosApi.isAllowedBaseUrl("https://user:pass@cosmos.naikio.in"))
        assertFalse(KtorCosmosApi.isAllowedBaseUrl("ftp://cosmos.naikio.in"))
        assertFailsWith<IllegalArgumentException> { KtorCosmosApi("http://example.com", "v", MockEngine { respond("") }) }
    }

    @Test fun given_a_run_log_when_uploaded_then_it_posts_gzipped_json_with_the_bearer_token() = runBlocking {
        val body = """{"runId":"r1","header":{"type":"run.start","runId":"r1"},"events":[],"end":null,"truncated":false}"""
        val status = api { json("""{"runId":"r1","status":"stored"}""") }.uploadRun("test-access", body)
        assertEquals("stored", status)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://cosmos.test/api/diagnostics/runs", request.url.toString())
        assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
        assertEquals("1.1.0+build.7.gabc1234", request.headers[KtorCosmosApi.APP_VERSION_HEADER])
        val content = request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent
        assertEquals("gzip", request.headers[HttpHeaders.ContentEncoding] ?: content.headers[HttpHeaders.ContentEncoding])
        val inflated = java.util.zip.GZIPInputStream(content.bytes().inputStream()).readBytes().decodeToString()
        assertEquals(body, inflated)
    }

    @Test fun given_a_too_large_or_malformed_run_when_uploaded_then_it_is_rejected() = runBlocking {
        val tooLarge = assertFailsWith<CosmosApiException.Rejected> { api { json("""{"error":"Too large"}""", HttpStatusCode.PayloadTooLarge) }.uploadRun("a", "{}") }
        assertEquals(413, tooLarge.status)
        val malformed = assertFailsWith<CosmosApiException.Rejected> { api { json("""{"error":"Bad"}""", HttpStatusCode.BadRequest) }.uploadRun("a", "{}") }
        assertEquals(400, malformed.status)
    }

    @Test fun given_the_update_check_when_called_then_it_asks_without_a_token_and_404_means_nothing_published() = runBlocking {
        val latest = api { json("""{"platform":"macos","version":"1.1.1","build":171,"commit":"abc1234","releasedAt":"2026-11-28T12:00:00.000Z","downloadUrl":"https://cosmos.test/downloads"}""") }.latestBuild("macos")
        assertEquals(com.jagdeep.shared.update.LatestBuild("1.1.1", 171, "https://cosmos.test/downloads"), latest)
        assertEquals("https://cosmos.test/api/app/macos", requests.last().url.toString())
        assertEquals(null, requests.last().headers[HttpHeaders.Authorization])
        assertEquals(null, api { json("""{"error":"Not found"}""", HttpStatusCode.NotFound) }.latestBuild("linux"))
    }
}
