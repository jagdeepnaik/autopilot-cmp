package com.jagdeep.autopilot

import com.jagdeep.home.domain.CourseCertificateStore
import com.jagdeep.home.domain.NoCourseCertificateStore

internal actual fun createCourseCertificateStore(): CourseCertificateStore = NoCourseCertificateStore
