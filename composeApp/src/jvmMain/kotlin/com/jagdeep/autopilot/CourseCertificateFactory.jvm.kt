package com.jagdeep.autopilot

import com.jagdeep.home.data.DownloadsCourseCertificateStore
import com.jagdeep.home.domain.CourseCertificateStore

internal actual fun createCourseCertificateStore(): CourseCertificateStore = DownloadsCourseCertificateStore()
