package com.jagdeep.autopilot.integration

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import kotlin.test.*

/** Isolated browser and synthetic account-free e-book. Never touches the user's Chrome profile. */
class DgsCollectorBrowserTest {
    @Test fun `given dynamically written blank iframe then scroll stop for video confirm rate and navigate once`() = verify(false)
    @Test fun `given course home then only selected available course opens before reading`() = verify(true)
    @Test fun `given mouse-down course control then app click uses normal press and release`() = verify(true, mouseDown=true)
    @Test fun `given legacy left mouse click check then course opens with which one`() = verify(true, leftMouse=true)
    private fun verify(fromHome: Boolean, mouseDown: Boolean = false, leftMouse: Boolean = false) {
        val chrome=System.getenv("AUTOPILOT_LIVE_CHROME_TEST") ?: return
        val readings=CopyOnWriteArrayList<JsonObject>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val origin="http://127.0.0.1:${server.address.port}"
        val script=checkNotNull(javaClass.getResourceAsStream("/chrome-extension/dgs-elearning.js")).bufferedReader().use { it.readText() }.replace("https://dgsstcwlms.aduacademy.in",origin)
        val html="""<ul><li data-node="0"><span data-v="page1" data-sel></span></li><li data-node="0"><span data-v="page2"></span></li></ul><iframe id="fraebookcontent" style="width:800px;height:600px" src="/libhtml/blank.htm"></iframe><script>
        window.chrome={runtime:{id:'test',sendMessage:async m=>await(await fetch('/reading',{method:'POST',body:JSON.stringify(m.reading)})).json()}};
        const frame=document.querySelector('iframe');frame.onload=()=>{const d=frame.contentDocument;d.body.innerHTML='<li id="niprevious" data-show="0">Previous</li><li id="ninext" data-show="1">Next</li><div id="divebook" style="height:350px;overflow:auto"><h1>First page</h1><div style="height:500px">Reading</div><video style="width:400px;height:200px"></video><div style="height:450px">End</div></div>';
        const v=d.querySelector('video');let ended=false,paused=true;Object.defineProperties(v,{ended:{get:()=>ended},paused:{get:()=>paused},readyState:{get:()=>4}});v.play=async()=>{paused=false;setTimeout(()=>{ended=true;paused=true},1600)};
        let clicks=0;d.querySelector('#ninext').onclick=()=>{clicks++;fetch('/clicked?count='+clicks);document.querySelector('[data-sel]').removeAttribute('data-sel');document.querySelector('[data-v=page2]').setAttribute('data-sel','');};};
        </script><script src="/collector.js"></script>"""
        val home="""<fieldset data-bc="program"><legend data-act="1"><span data-bat>Program heading is not a course link</span><span data-des>18</span></legend><div data-bcc="batch-course" data-scc="available"><span data-crs="1" onclick="location.href='/form/ebook'">Example course</span></div></fieldset><fieldset data-bc="expired"><legend data-act="0">Expired program</legend><div data-bcc="old-course" data-scc="locked"><span data-crs="0">Old course</span></div></fieldset><script>window.chrome={runtime:{id:'test',sendMessage:async m=>await(await fetch('/reading',{method:'POST',body:JSON.stringify(m.reading)})).json()}};</script><script src="/collector.js"></script>"""
        val homePage=if(mouseDown) home.replace("onclick=", "onmousedown=") else if(leftMouse) home.replace("onclick=\"location.href", "onclick=\"if(event.which!==1 || event.detail!==1)return;location.href") else home
        var clicks=0
        server.createContext("/") { x ->
            val path=x.requestURI.path
            val body=when(path) {
                "/collector.js" -> script
                "/form/studenthome" -> homePage
                "/libhtml/blank.htm" -> "<html><body></body></html>"
                "/clicked" -> {clicks++; "ok"}
                "/reading" -> {
                    val r=Json.parseToJsonElement(x.requestBody.bufferedReader().readText()).jsonObject;readings+=r
                    val media=r["media"]?.jsonPrimitive?.content
                    val selecting=r["courses"]?.jsonArray?.isNotEmpty()==true
                    val action=if(selecting) "selectCourse" else if(r["atBottom"]?.jsonPrimitive?.boolean==true && media in listOf("none","ended")) "next" else "read"
                    buildJsonObject {put("session","fixture");put("page",r["page"]!!);put("action",action);put("pixelsPerSecond",400);put("playbackSpeed",2.0);if(selecting) {put("courseId","available");put("courseLabel","Example course")}}.toString()
                }
                else -> html
            }.toByteArray()
            x.responseHeaders.add("Content-Type",if(path=="/collector.js") "text/javascript" else if(path=="/reading") "application/json" else "text/html")
            x.sendResponseHeaders(200,body.size.toLong());x.responseBody.use {it.write(body)}
        }
        server.start()
        val folder=Files.createTempDirectory("dgs-browser").toFile()
        val process=ProcessBuilder(chrome,"--headless=new","--no-first-run","--user-data-dir=${folder.absolutePath}","$origin/form/${if(fromHome) "studenthome" else "ebook"}").redirectErrorStream(true).redirectOutput(File(folder,"chrome.log")).start()
        try {
            val deadline=System.currentTimeMillis()+20000
            while((clicks==0 || readings.filter { it["ready"]?.jsonPrimitive?.boolean==true }.map { it["page"] }.distinct().size<2) && System.currentTimeMillis()<deadline) Thread.sleep(50)
            if(fromHome) assertEquals(listOf("Example course","Old course"),readings.first {it["courses"]!=null}["courses"]!!.jsonArray.map {it.jsonObject["label"]!!.jsonPrimitive.content})
            assertEquals(1,clicks,"Must scroll, watch and click Next exactly once")
            assertTrue(readings.filter { it["ready"]?.jsonPrimitive?.boolean==true }.map { it["page"] }.distinct().size>=2,"Identical content still changes page identity when the outline selection changes")
            assertTrue(readings.any {it["media"]?.jsonPrimitive?.content=="playing"})
            assertTrue(readings.any {it["media"]?.jsonPrimitive?.content=="ended" && it["speed"]?.jsonPrimitive?.double==2.0})
            assertTrue(readings.any {it["previous"]?.jsonPrimitive?.boolean==false},"Hidden Previous is a verified beginning, not an unknown boundary")
        } finally {process.destroyForcibly().waitFor();server.stop(0);folder.deleteRecursively()}
    }
}
