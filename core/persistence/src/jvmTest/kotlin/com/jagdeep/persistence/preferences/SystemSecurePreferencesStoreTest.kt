package com.jagdeep.persistence.preferences

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class SystemSecurePreferencesStoreTest {
    @Serializable private data class Secret(val token: String, val name: String)

    private class MemoryBackend : SecretBackend {
        var value: String? = null
        var failWrites = false
        override fun read() = value
        override fun write(value: String) { if (failWrites) throw IOException("unavailable"); this.value = value }
        override fun delete() { value = null }
    }

    /** The same expectations for every backend: save, load in a new store, clear. */
    private fun contract(backend: () -> SecretBackend) = runBlocking {
        val store = SystemSecurePreferencesStore(backend(), Secret.serializer())
        store.clear()
        assertNull(store.data.first())
        store.save(Secret("test-token-1", "Tester"))
        assertEquals(Secret("test-token-1", "Tester"), store.data.first())
        // A new store (a new app start) reads what was saved.
        assertEquals(Secret("test-token-1", "Tester"), SystemSecurePreferencesStore(backend(), Secret.serializer()).data.first())
        store.save(Secret("test-token-2", "Tester"))
        assertEquals("test-token-2", SystemSecurePreferencesStore(backend(), Secret.serializer()).data.first()?.token)
        store.clear()
        assertNull(store.data.first())
        assertNull(SystemSecurePreferencesStore(backend(), Secret.serializer()).data.first())
    }

    @Test fun given_a_fake_backend_when_saving_and_clearing_then_the_contract_holds() {
        val shared = MemoryBackend()
        contract { shared }
    }

    @Test fun given_a_backend_that_cannot_write_when_saving_then_it_fails_and_nothing_is_kept() = runBlocking {
        val backend = MemoryBackend().apply { failWrites = true }
        val store = SystemSecurePreferencesStore(backend, Secret.serializer())
        assertFailsWith<IOException> { store.save(Secret("test-token", "Tester")) }
        assertNull(store.data.first())
    }

    @Test fun given_macos_when_using_the_keychain_then_the_contract_holds() {
        if (!System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")) return
        val service = "com.jagdeep.persistence.test-" + System.nanoTime()
        contract { MacKeychainSecretBackend(service, "contract-test") }
        MacKeychainSecretBackend(service, "contract-test").delete()
    }
}
