package com.jagdeep.home.domain.cosmos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MachineIdentityTest {
    private fun source(platform: String?, raw: String?) = object : MachineIdentifierSource {
        override val platform = platform
        override fun rawIdentifier() = raw
    }

    // Expected values: lowercase hex SHA-256 of "autopilot-machine-v1:" + identifier, computed independently.
    @Test fun given_a_macos_platform_uuid_then_the_contract_hash_is_produced() {
        assertEquals("a39c26f92e352bff0604c16b059e66ed02f3ac72144a984a4e645a1fc1655b7d", MachineIdentity.hash("12345678-90AB-CDEF-1234-567890ABCDEF"))
    }

    @Test fun given_a_windows_machine_guid_then_the_contract_hash_is_produced() {
        assertEquals("c5d1349c0fde18981e207de661e8a874d106c2d95f573fe55b1fa1ca83956d00", MachineIdentity.hash("b1c2d3e4-0000-4000-8000-1234567890ab"))
    }

    @Test fun given_a_linux_machine_id_with_its_newline_then_the_contract_hash_is_produced() {
        assertEquals("697911718b1edd1dce7cfc155dbbd1b517d08f9d367905e49b2b67ff80367943", MachineIdentity.hash("0123456789abcdef0123456789abcdef\n"))
    }

    @Test fun given_an_android_id_then_the_contract_hash_and_platform_are_produced() {
        val identity = MachineIdentity.of(source("android", "9774d56d682e549c"))!!
        assertEquals("98ebedb13f1583c4e03eef5479af929b185667238ac2f32b91556b26b64a65ee", identity.machineId)
        assertEquals("android", identity.platform)
    }

    @Test fun given_a_missing_blank_or_failing_identifier_or_an_unknown_platform_then_there_is_no_identity() {
        assertNull(MachineIdentity.of(source("macos", null)))
        assertNull(MachineIdentity.of(source("macos", "  ")))
        assertNull(MachineIdentity.of(source("ios", "something")))
        assertNull(MachineIdentity.of(source(null, "something")))
        assertNull(MachineIdentity.of(object : MachineIdentifierSource {
            override val platform = "linux"
            override fun rawIdentifier(): String = error("unreadable")
        }))
        assertNull(MachineIdentity.of(NoMachineIdentifier))
    }

    @Test fun given_an_identity_then_printing_it_never_shows_the_id() {
        val identity = MachineIdentity.of(source("windows", "b1c2d3e4-0000-4000-8000-1234567890ab"))!!
        assertFalse(identity.toString().contains(identity.machineId))
        assertFalse(identity.toString().contains("b1c2d3e4"))
    }
}
