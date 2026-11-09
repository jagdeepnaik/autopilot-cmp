package com.jagdeep.autoclicker

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.serialization.json.*

/** Real extension collector against a local DOM fixture, with no signed-in account or attempts. */
class ProgressCollectorBrowserTest {
    @Test fun `given rendered progress table then collect header labels profile pagination and nonmonotonic action ids`() = verify(false)
    @Test fun `given rows per page dropdown then discover and select the maximum using typed clicks`() = verify(true)

    private fun verify(selectMaximum: Boolean) {
        val chrome = System.getenv("AUTOCLICKER_LIVE_CHROME_TEST") ?: return
        val captured = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val script = checkNotNull(javaClass.getResourceAsStream("/chrome-extension/course-automation.js")).bufferedReader().use { it.readText() }.replace("https://sagarmeinyog.com",origin)
        val html = """
            <main><nav><a href="/dashboard">HOME</a><svg class="lucide-chevron-right"></svg></nav><button aria-haspopup="menu"><div>AB</div><span>Example User</span></button>
            <h3>Test Performance Analytics</h3><button role="combobox">All Time</button>
            <table><thead><tr>
            <th><div><div>Course Name</div></div></th><th><div><div>Test Type</div><button role="combobox">All</button></div></th>
            <th><div><div>Passed</div><button role="combobox">All</button></div></th>
            <th>Test Title</th><th>Attempt Number</th><th>Started At</th><th>Completed At</th><th>Percentage</th>
            </tr></thead><tbody><tr><td>Wellness</td><td>ASSESSMENT</td><td>YES</td><td>Course Assessment</td><td>1</td><td>Today</td><td>Today</td><td>90%</td></tr></tbody></table>
            <p>Rows per page</p><button id="size" role="combobox" aria-controls="sizes" aria-expanded="false" onclick="document.getElementById('sizes').style.display='block';this.setAttribute('aria-expanded','true')">10</button>
            <div id="sizes" role="listbox" style="display:none">
            <div role="option" onclick="chooseSize(10)">10</div><div role="option" onclick="chooseSize(50)">50</div><div role="option" onclick="chooseSize(100)">100</div></div><span id="page">Page 1 of 2</span><span>2 total entries</span>
            <button onclick="document.getElementById('page').innerText='Page 2 of 2';this.disabled=true"><svg class="lucide-chevron-right" width="20" height="20"></svg></button>
            <button id="finish" onclick="fetch('/finish')">Finished fixture</button></main>
            <script>function chooseSize(value){const size=document.getElementById('size');size.innerText=String(value);size.setAttribute('aria-expanded','false');document.getElementById('sizes').style.display='none';}const session='fixture';let sent=false;window.chrome={runtime:{id:'fixture',sendMessage:async m=>{
            if(m.type==='courseHello')return {session};
            if(m.type==='courseInventory')return await (await fetch('/inventory',{method:'POST',body:JSON.stringify(m.snapshot)})).json();
            return {session};}}};</script><script src="/collector.js"></script>
        """.trimIndent()
        var finished = false
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when(path) {
                "/collector.js" -> script
                "/inventory" -> {
                    val value = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    captured += value
                    val table = value["progress"]?.jsonObject
                    val action = if(table?.get("ready")?.jsonPrimitive?.boolean == true) {
                        val page = table["page"]!!.jsonPrimitive.int
                        var kind = "click"
                        var expected: String? = null
                        val id = if(selectMaximum && table["pageSize"]?.jsonPrimitive?.int != 100) {
                            kind = "progressPageSize"
                            if(table["pageSizeOpen"]!!.jsonPrimitive.boolean) {
                                val choice = table["pageSizeOptions"]!!.jsonArray.maxBy { it.jsonObject["value"]!!.jsonPrimitive.int }.jsonObject
                                expected = choice["value"]!!.jsonPrimitive.content
                                choice["id"]!!.jsonPrimitive.content
                            } else {
                                expected = table["pageSize"]!!.jsonPrimitive.content
                                table["pageSizeControl"]!!.jsonPrimitive.content
                            }
                        } else if(page == 1) table["next"]?.jsonPrimitive?.content else value["elements"]!!.jsonArray.first { it.jsonObject["label"]?.jsonPrimitive?.content == "Finished fixture" }.jsonObject["id"]!!.jsonPrimitive.content
                        val request = if(kind == "progressPageSize") 1_000_000_000L + captured.size else if(page == 1) 2_000_000_000L else 1L
                        buildJsonObject { put("request",request); put("document",value["document"]!!); put("element",id); put("kind",kind); expected?.let { put("expectedLabel",it) } }

                    } else JsonNull
                    buildJsonObject { put("session","fixture"); put("action",action) }.toString()
                }
                "/finish" -> { finished = true; "ok" }
                else -> html
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type",if(path=="/collector.js") "text/javascript" else if(path=="/inventory") "application/json" else "text/html")
            exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val folder = Files.createTempDirectory("progress-browser").toFile()
        val process = ProcessBuilder(chrome,"--headless=new","--no-first-run","--user-data-dir=${folder.absolutePath}","$origin/dashboard/progress").redirectErrorStream(true).redirectOutput(File(folder,"chrome.log")).start()
        try {
            val deadline = System.currentTimeMillis()+25_000
            while(!finished && System.currentTimeMillis()<deadline) Thread.sleep(50)
            assertTrue(finished,"Collector must execute the low-numbered course action after a high-numbered progress action: $captured")
            val snapshot = captured.first { it["progress"]?.jsonObject?.get("ready")?.jsonPrimitive?.boolean == true }
            assertEquals("Example User",snapshot["profileName"]!!.jsonPrimitive.content)
            val table = snapshot["progress"]!!.jsonObject
            assertTrue(table["unfiltered"]!!.jsonPrimitive.boolean)
            assertEquals("ASSESSMENT",table["rows"]!!.jsonArray.first().jsonObject["Test Type"]!!.jsonPrimitive.content)
            assertEquals("YES",table["rows"]!!.jsonArray.first().jsonObject["Passed"]!!.jsonPrimitive.content)
            if(selectMaximum) {
                assertTrue(captured.any { it["progress"]?.jsonObject?.get("pageSize")?.jsonPrimitive?.intOrNull == 100 })
                assertTrue(captured.any { it["progress"]?.jsonObject?.get("pageSizeOptions")?.jsonArray?.any { option -> option.jsonObject["value"]!!.jsonPrimitive.int == 100 } == true })
            }

        } finally { process.destroyForcibly().waitFor(); server.stop(0); folder.deleteRecursively() }
    }
}
