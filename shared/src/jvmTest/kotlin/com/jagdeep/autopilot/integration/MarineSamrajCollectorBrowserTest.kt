package com.jagdeep.autopilot.integration

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real DOM regression test in an isolated browser profile; no account or live site is used. */
class MarineSamrajCollectorBrowserTest {
    @Test fun `given delayed public answer when loaded then capture exact editor and navigate once`() {
        val chrome = System.getenv("AUTOPILOT_LIVE_CHROME_TEST") ?: return
        val readings = CopyOnWriteArrayList<JsonObject>()
        val acknowledgements = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val collector = checkNotNull(javaClass.getResourceAsStream("/chrome-extension/marinesamraj.js")).bufferedReader().use { it.readText() }
            .replace("https://www.marinesamraj.com", origin).replace("https://marinesamraj.com", origin)
            .replace("'https:'", "'http:'").replace("'marinesamraj.com'", "'127.0.0.1'")
        val answer = "Published reference paragraph. ".repeat(900)
        fun html(second: Boolean): String = """<!doctype html><html><body>
          <div style="display:none"><h1>Stale hidden question</h1><section aria-label="Answer"><div class="ql-editor">Wrong stale answer.</div></section></div>
          <a href="/exam-prep/subject/fixture">Example subject</a><h1>Question</h1>
          <h1 id="question">${if (second) "Second published question" else ""}</h1>
          <section aria-label="Answer"><h2>Answer</h2><p>Posted yesterday</p><div class="ql-editor" id="answer">${if (second) "Second reference answer." else ""}</div><p>Author and footer must be excluded</p></section>
          ${if (second) "" else "<button onclick=\"location.href='/exam-prep/question/two'\">NEXT</button>"}
          <script>window.chrome={runtime:{sendMessage:async m=>m.type==='marineSamrajHello'?{session:'fixture'}:await(await fetch('/event',{method:'POST',body:JSON.stringify(m)})).json()}};
          ${if (second) "" else "setTimeout(()=>{document.querySelector('#question').textContent='First published question';document.querySelector('#answer').textContent='${answer}';},1200);"}
          </script><script src="/collector.js"></script></body></html>"""
        server.createContext("/") { exchange ->
            val requestPath = exchange.requestURI.path
            val body = when (requestPath) {
                "/collector.js" -> collector
                "/event" -> {
                    val event = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    if (event["type"]?.jsonPrimitive?.content == "marineSamrajReading") readings += event["reading"]!!.jsonObject
                    if (event["type"]?.jsonPrimitive?.content == "marineSamrajAction") acknowledgements += event
                    val value = event["reading"]?.jsonObject
                    val control = value?.get("controls")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                    buildJsonObject {
                        put("session", "fixture")
                        if (value?.get("ready")?.jsonPrimitive?.boolean == true && control != null) put("action", buildJsonObject {
                            put("request", 1); put("document", value["document"]!!); put("url", value["url"]!!); put("control", control)
                        })
                    }.toString()
                }
                else -> html(requestPath.endsWith("/two"))
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (requestPath == "/collector.js") "text/javascript" else if (requestPath == "/event") "application/json" else "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val folder = Files.createTempDirectory("marinesamraj-browser").toFile()
        val process = ProcessBuilder(chrome, "--headless=new", "--no-first-run", "--user-data-dir=${folder.absolutePath}", "$origin/exam-prep/question/one")
            .redirectErrorStream(true).redirectOutput(File(folder, "chrome.log")).start()
        try {
            val deadline = System.currentTimeMillis() + 20000
            while (readings.none { it["url"]?.jsonPrimitive?.content?.endsWith("/two") == true && it["ready"]?.jsonPrimitive?.boolean == true } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val first = readings.firstOrNull { it["question"]?.jsonPrimitive?.content == "First published question" && it["ready"]?.jsonPrimitive?.boolean == true }
            assertNotNull(first, "Must wait for asynchronous content and stable DOM")
            assertEquals(answer.trim(), first["answer"]?.jsonPrimitive?.content)
            assertEquals(1, acknowledgements.size, "Exactly one click, bound to the original route")
            assertTrue(readings.any { it["ready"]?.jsonPrimitive?.boolean == false })
            assertEquals(2, readings.filter { it["ready"]?.jsonPrimitive?.boolean == true }.map { it["document"] }.distinct().size)
        } finally { process.destroyForcibly().waitFor(); server.stop(0); folder.deleteRecursively() }
    }
}
