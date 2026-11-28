package com.jagdeep.autopilot.platform

import com.jagdeep.home.data.compass.KtorCompassApi
import com.jagdeep.home.domain.NoCourseAnswerBankStore
import com.jagdeep.shared.compass.NoCompassSync
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class CompassSyncFactoryTest {
    @Test fun given_the_build_config_then_the_compass_address_is_https_and_set_once() {
        val url = compassBaseUrl()!!
        assertTrue(KtorCompassApi.isAllowedBaseUrl(url), url)
    }

    @Test fun given_no_routine_q_and_a_files_then_sync_is_not_offered() {
        assertSame(NoCompassSync, createCompassSync(NoCourseAnswerBankStore, CoroutineScope(Dispatchers.Unconfined), RunLogServices(com.jagdeep.shared.diagnostics.NoRunLog, com.jagdeep.shared.diagnostics.NoRunLogFiles, com.jagdeep.home.domain.compass.NoRunLogOutbox, kotlinx.coroutines.flow.emptyFlow())) {})
    }
}
