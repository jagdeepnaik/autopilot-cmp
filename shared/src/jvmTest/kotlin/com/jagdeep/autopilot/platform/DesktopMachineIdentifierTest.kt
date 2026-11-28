package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.cosmos.MachineIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMachineIdentifierTest {
    private val ioreg = """
        +-o J316sAP  <class IOPlatformExpertDevice, id 0x100000234, registered, matched, active, busy 0 (0 ms), retain 39>
            {
              "IOPlatformSerialNumber" = "TESTSERIAL"
              "IOPlatformUUID" = "12345678-90AB-CDEF-1234-567890ABCDEF"
              "model" = <"MacBookPro18,1">
            }
    """.trimIndent()

    private val reg = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography\r\n    MachineGuid    REG_SZ    b1c2d3e4-0000-4000-8000-1234567890ab\r\n\r\n"

    @Test fun given_macos_ioreg_output_then_the_platform_uuid_is_hashed_per_the_contract() {
        val commands = mutableListOf<List<String>>()
        val source = DesktopMachineIdentifier("Mac OS X", run = { commands += it; ioreg }, readFile = { null }, windowsRegistry = { null })
        val identity = MachineIdentity.of(source)!!
        assertEquals("macos", identity.platform)
        assertEquals("a39c26f92e352bff0604c16b059e66ed02f3ac72144a984a4e645a1fc1655b7d", identity.machineId)
        assertEquals(listOf("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), commands.single())
    }

    @Test fun given_windows_then_the_registry_value_is_used_and_reg_query_is_the_fallback() {
        val fromRegistry = DesktopMachineIdentifier("Windows 11", run = { error("not needed") }, readFile = { null }, windowsRegistry = { "b1c2d3e4-0000-4000-8000-1234567890ab" })
        assertEquals("c5d1349c0fde18981e207de661e8a874d106c2d95f573fe55b1fa1ca83956d00", MachineIdentity.of(fromRegistry)!!.machineId)
        val fromCommand = DesktopMachineIdentifier("Windows 11", run = { reg }, readFile = { null }, windowsRegistry = { null })
        assertEquals("windows", fromCommand.platform)
        assertEquals("c5d1349c0fde18981e207de661e8a874d106c2d95f573fe55b1fa1ca83956d00", MachineIdentity.of(fromCommand)!!.machineId)
    }

    @Test fun given_linux_then_etc_machine_id_is_read_and_the_dbus_copy_is_the_fallback() {
        val reads = mutableListOf<String>()
        val source = DesktopMachineIdentifier("Linux", run = { null }, readFile = { file -> reads += file.path; if (file.path == "/var/lib/dbus/machine-id") "0123456789abcdef0123456789abcdef\n" else null }, windowsRegistry = { null })
        assertEquals("697911718b1edd1dce7cfc155dbbd1b517d08f9d367905e49b2b67ff80367943", MachineIdentity.of(source)!!.machineId)
        assertEquals(listOf("/etc/machine-id", "/var/lib/dbus/machine-id"), reads)
    }

    @Test fun given_unusable_output_then_there_is_no_identifier() {
        assertNull(DesktopMachineIdentifier.parseIoregPlatformUuid("no uuid here"))
        assertNull(DesktopMachineIdentifier.parseRegMachineGuid("ERROR: The system was unable to find the specified registry key or value."))
        assertNull(DesktopMachineIdentifier.parseMachineIdFile("uninitialized\n"))
        assertNull(DesktopMachineIdentifier.parseMachineIdFile(""))
        assertNull(MachineIdentity.of(DesktopMachineIdentifier("Mac OS X", run = { null }, readFile = { null }, windowsRegistry = { null })))
        assertNull(DesktopMachineIdentifier("SunOS", run = { ioreg }, readFile = { "x" }, windowsRegistry = { "x" }).platform)
    }

    @Test fun given_this_computer_then_an_identifier_is_found_when_the_platform_has_one() {
        val source = createMachineIdentifierSource()
        if (source.platform == null) return
        val identity = MachineIdentity.of(source)
        // Build machines (containers without /etc/machine-id) may have none; when found it is the contract's hex.
        identity?.let { assertEquals(64, it.machineId.length); assertEquals(source.platform, it.platform) }
    }
}
