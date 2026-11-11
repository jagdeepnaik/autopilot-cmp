package com.jagdeep.home.domain

/** A downloaded assessment certificate, kept separately for each website profile and module. */
data class CourseCertificateTarget(val profileName: String, val module: String)

/**
 * Platform file boundary for course certificates. The runner only asks whether a certificate is
 * present or whether a download requested moments ago has finished; it never receives a path.
 */
interface CourseCertificateStore {
    fun hasCertificate(target: CourseCertificateTarget): Boolean
    fun prepareDownload(target: CourseCertificateTarget)
    fun collectDownloaded(target: CourseCertificateTarget): Boolean
    /** Records the user's confirmation after an unavailable automatic download. */
    fun confirmDownloaded(target: CourseCertificateTarget)
    fun recordResultUrl(target: CourseCertificateTarget, url: String)
}

object NoCourseCertificateStore : CourseCertificateStore {
    override fun hasCertificate(target: CourseCertificateTarget) = false
    override fun prepareDownload(target: CourseCertificateTarget) = Unit
    override fun collectDownloaded(target: CourseCertificateTarget) = false
    override fun confirmDownloaded(target: CourseCertificateTarget) = Unit
    override fun recordResultUrl(target: CourseCertificateTarget, url: String) = Unit
}
