package com.jagdeep.autopilot.platform

import com.jagdeep.home.data.cosmos.KtorCosmosApi
import com.jagdeep.home.domain.NoCourseAnswerBankStore
import com.jagdeep.shared.cosmos.NoCosmosSync
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class CosmosSyncFactoryTest {
    @Test fun given_the_build_config_then_the_cosmos_address_is_https_and_set_once() {
        val url = cosmosBaseUrl()!!
        assertTrue(KtorCosmosApi.isAllowedBaseUrl(url), url)
    }

    @Test fun given_no_routine_q_and_a_files_then_sync_is_not_offered() {
        assertSame(NoCosmosSync, createCosmosSync(NoCourseAnswerBankStore, CoroutineScope(Dispatchers.Unconfined), RunLogServices(com.jagdeep.shared.diagnostics.NoRunLog, com.jagdeep.shared.diagnostics.NoRunLogFiles, com.jagdeep.home.domain.cosmos.NoRunLogOutbox, kotlinx.coroutines.flow.emptyFlow())) {})
    }
}
