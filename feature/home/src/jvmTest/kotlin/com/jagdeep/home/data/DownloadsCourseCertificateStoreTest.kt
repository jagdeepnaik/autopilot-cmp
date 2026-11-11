package com.jagdeep.home.data

import com.jagdeep.home.domain.CourseCertificateTarget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsCourseCertificateStoreTest {
    @Test
    fun `given a stable new pdf then it is saved under the profile and module`() {
        val downloads = Files.createTempDirectory("autopilot-downloads").toFile()
        try {
            val store = DownloadsCourseCertificateStore(downloads)
            val target = CourseCertificateTarget("Jagdeep Shivdas Naik", "Spiritual Wellness")
            store.prepareDownload(target)
            val pdf = downloads.resolve("certificate.pdf")
            pdf.writeBytes(byteArrayOf(1, 2, 3))

            assertFalse(store.collectDownloaded(target), "The first observation waits for Chrome to finish writing")
            assertTrue(store.collectDownloaded(target))
            assertTrue(store.hasCertificate(target))
            assertTrue(downloads.resolve("AutoPilot/Jagdeep Shivdas Naik/Spiritual Wellness.pdf").isFile)
            store.recordResultUrl(target, "https://sagarmeinyog.com/dashboard/courses/module/assessment/result/result")
            assertTrue(downloads.resolve("AutoPilot/Jagdeep Shivdas Naik/passed-result-urls.txt").readText().contains("Spiritual Wellness"))
        } finally {
            downloads.deleteRecursively()
        }
    }

    @Test
    fun `given user confirmation then certificate completion is retained without a local pdf`() {
        val downloads = Files.createTempDirectory("autopilot-downloads").toFile()
        try {
            val store = DownloadsCourseCertificateStore(downloads)
            val target = CourseCertificateTarget("Jagdeep Shivdas Naik", "Emotional Wellness")

            store.confirmDownloaded(target)

            assertTrue(store.hasCertificate(target))
            assertTrue(downloads.resolve("AutoPilot/Jagdeep Shivdas Naik/certificate-completions.txt").readText().contains("Emotional Wellness"))
        } finally {
            downloads.deleteRecursively()
        }
    }
}
