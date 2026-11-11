package com.jagdeep.home.data

import com.jagdeep.home.domain.CourseCertificateStore
import com.jagdeep.home.domain.CourseCertificateTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Moves PDFs Chrome has just downloaded into ~/Downloads/AutoPilot/<profile>/ by module. */
class DownloadsCourseCertificateStore(
    private val downloads: File = File(System.getProperty("user.home"), "Downloads"),
) : CourseCertificateStore {
    private val startedAt = mutableMapOf<CourseCertificateTarget, Long>()

    override fun hasCertificate(target: CourseCertificateTarget): Boolean =
        (destination(target).isFile && destination(target).length() > 0) || confirmed(target)

    override fun prepareDownload(target: CourseCertificateTarget) {
        startedAt[target] = System.currentTimeMillis()
    }

    override fun collectDownloaded(target: CourseCertificateTarget): Boolean {
        if (hasCertificate(target)) return true
        val since = startedAt[target] ?: return false
        val source = downloads.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("pdf", true) && it.lastModified() >= since }
            ?.filterNot { File(it.parentFile, "${it.name}.crdownload").exists() }
            ?.maxByOrNull { it.lastModified() }
            ?: return false
        // A just-created PDF can still be growing. Two unchanged observations prevent moving it early.
        val length = source.length()
        if (length <= 0 || observedSizes.put(source.absolutePath, length) != length) return false
        val destination = destination(target)
        destination.parentFile.mkdirs()
        // Finder/Chrome can still be resolving the completed download immediately after it
        // appears. Moving it at that point makes Finder show a spurious “can't be found” alert.
        // Copy into AutoPilot first, then remove Chrome's temporary download after its handoff.
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        cleanup.schedule({ runCatching { Files.deleteIfExists(source.toPath()) } }, 10, TimeUnit.SECONDS)
        startedAt.remove(target)
        observedSizes.remove(source.absolutePath)
        return true
    }

    override fun confirmDownloaded(target: CourseCertificateTarget) {
        // Prefer moving a just-downloaded PDF into the managed folder. If Chrome did not expose a
        // usable file, preserve the user's explicit confirmation as the completion record.
        if (!collectDownloaded(target) && !hasCertificate(target)) {
            val file = File(destination(target).parentFile, "certificate-completions.txt")
            file.parentFile.mkdirs()
            val entry = target.module
            if (entry !in file.takeIf(File::isFile)?.readLines().orEmpty()) file.appendText("$entry\n")
        }
    }

    override fun recordResultUrl(target: CourseCertificateTarget, url: String) {
        if (!url.startsWith("https://sagarmeinyog.com/dashboard/courses/")) return
        val file = File(destination(target).parentFile, "passed-result-urls.txt")
        file.parentFile.mkdirs()
        val entry = "${target.module}\t$url"
        val existing = file.takeIf(File::isFile)?.readLines().orEmpty()
        // A result page can initially expose an internal course id before its title loads. Keep
        // only the best, latest title for a given URL.
        val retained = existing.filterNot { it.substringAfter('\t', "") == url }
        if (retained + entry != existing) file.writeText((retained + entry).joinToString("\n", postfix = "\n"))
    }

    private fun destination(target: CourseCertificateTarget): File = File(
        File(File(downloads, "AutoPilot"), safe(target.profileName)), "${safe(target.module)}.pdf",
    )

    private fun confirmed(target: CourseCertificateTarget): Boolean =
        File(destination(target).parentFile, "certificate-completions.txt").takeIf(File::isFile)
            ?.readLines()?.any { it == target.module } == true

    private fun safe(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim('.',' ').take(100).ifBlank { "Unknown profile" }

    private companion object {
        val observedSizes = mutableMapOf<String, Long>()
        val cleanup = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "autopilot-certificate-cleanup").apply { isDaemon = true }
        }
    }
}
