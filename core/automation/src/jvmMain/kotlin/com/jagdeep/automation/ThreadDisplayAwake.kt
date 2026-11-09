package com.jagdeep.automation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Windows execution-state requests and release must run on the same long-lived thread. */
internal class ThreadDisplayAwake(private val request: (Int) -> Boolean) {
    private data class Hold(val release: CountDownLatch = CountDownLatch(1), val finished: CountDownLatch = CountDownLatch(1))
    private var hold: Hold? = null
    @Synchronized fun setAwake(awake: Boolean) {
        if(!awake) {
            val current=hold ?: return
            hold=null
            current.release.countDown()
            check(current.finished.await(3,TimeUnit.SECONDS)) { "Display wake release did not finish" }
            return
        }
        if(hold != null) return
        val current=Hold()
        val started=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        thread(isDaemon=true,name="display-awake") {
            try {
                check(request(DISPLAY_AND_SYSTEM)) { "The operating system refused the display wake request" }
                started.countDown()
                current.release.await()
            } catch(error:Throwable) { failure.set(error) }
            finally {
                started.countDown()
                try { request(RELEASE) } finally { current.finished.countDown() }
            }
        }
        if(!started.await(3,TimeUnit.SECONDS)) { current.release.countDown(); error("Display wake request did not finish") }
        failure.get()?.let { current.release.countDown(); throw IllegalStateException("Display wake request failed",it) }
        hold=current
    }
    companion object {
        internal const val RELEASE = Int.MIN_VALUE // ES_CONTINUOUS
        internal const val DISPLAY_AND_SYSTEM = RELEASE or 0x00000002 or 0x00000001
    }
}
