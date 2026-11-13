package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.CourseCertificateStore
import com.jagdeep.home.domain.NoCourseCertificateStore

internal actual fun createCourseCertificateStore(): CourseCertificateStore = NoCourseCertificateStore
