package org.matchat.core.contacts

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import org.matchat.core.policy.Policy
import org.matchat.core.policy.PolicyProvider

class DefaultContactsRepositoryTest {

    private class FakePolicy(policy: Policy) : PolicyProvider {
        override val policy: StateFlow<Policy> = MutableStateFlow(policy)
    }

    /** Same rules as SharedPreferencesLocalContactsStore, in memory. */
    private class FakeLocal(initial: List<Contact> = emptyList()) : LocalContactsStore {
        val savedState = MutableStateFlow(initial)
        val recentState = MutableStateFlow<List<UserId>>(emptyList())
        val removed = mutableSetOf<UserId>()
        val phoneSync = MutableStateFlow(false)
        var offered = false

        override val saved: Flow<List<Contact>> = savedState
        override val recents: Flow<List<Contact>> =
            recentState.map { list -> list.map { Contact(it, null, Contact.Source.LOCAL) } }
        override val phoneSyncEnabled: Flow<Boolean> = phoneSync

        override suspend fun addRecent(address: UserId) {
            recentState.value = (listOf(address) + recentState.value).distinct()
        }

        override suspend fun save(address: UserId, name: String?) {
            removed -= address
            val existing = savedState.value.firstOrNull { it.address == address }
            if (existing == null) {
                savedState.value = savedState.value + Contact(address, name, Contact.Source.LOCAL)
            } else if (name != null) {
                savedState.value = savedState.value.map { if (it.address == address) it.copy(name = name) else it }
            }
        }

        override suspend fun saveIfNew(address: UserId, name: String?): Boolean {
            if (address in removed || savedState.value.any { it.address == address }) return false
            savedState.value = savedState.value + Contact(address, name, Contact.Source.LOCAL)
            return true
        }

        override suspend fun remove(address: UserId) {
            savedState.value = savedState.value.filterNot { it.address == address }
            removed += address
        }

        override suspend fun setPhoneSyncEnabled(enabled: Boolean) {
            phoneSync.value = enabled
        }

        override fun isPhonePromptOffered(): Boolean = offered

        override fun markPhonePromptOffered() {
            offered = true
        }
    }

    /** Records what landed in the "phone's" Contacts app. */
    private class FakePhone(var allowed: Boolean = false, var mergedName: String? = null) : PhoneContacts {
        val onPhone = mutableMapOf<UserId, String?>()
        override fun isAllowed(): Boolean = allowed
        override suspend fun upsert(address: UserId, name: String?) {
            onPhone.getOrPut(address) { name }
        }
        override suspend fun mergeInto(contactUri: String, address: UserId): String? = mergedName
    }

    private val wayne = UserId("@wayne:example.org")
    private val merv = UserId("@merv:carpathianserver.org")

    private fun repo(
        local: FakeLocal = FakeLocal(),
        phone: FakePhone = FakePhone(),
        admin: List<Contact> = emptyList(),
    ) = DefaultContactsRepository(
        policyProvider = FakePolicy(Policy.UNMANAGED.copy(adminContacts = admin)),
        localStore = local,
        phone = phone,
    )

    @Test
    fun `admin first, deduplicated by address`() = runTest {
        val admin = Contact(wayne, "Wayne", Contact.Source.ADMIN)
        val localDup = Contact(wayne, null, Contact.Source.LOCAL)
        val localOnly = Contact(merv, null, Contact.Source.LOCAL)

        repo(local = FakeLocal(listOf(localDup, localOnly)), admin = listOf(admin)).contacts.test {
            assertEquals(listOf(admin, localOnly), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saved contacts are listed by name`() = runTest {
        val zed = Contact(UserId("@z:s"), "Zed", Contact.Source.LOCAL)
        val amy = Contact(UserId("@a:s"), "amy", Contact.Source.LOCAL)
        assertEquals(listOf(amy, zed), repo(local = FakeLocal(listOf(zed, amy))).contacts.first())
    }

    @Test
    fun `a recent that is already a contact is not listed twice`() = runTest {
        val local = FakeLocal(listOf(Contact(wayne, "Wayne", Contact.Source.LOCAL)))
        val repo = repo(local = local)
        repo.recordRecent(wayne)
        repo.recordRecent(merv)
        assertEquals(listOf(merv), repo.recents.first().map { it.address })
    }

    @Test
    fun `auto-save adds a new contact but never renames or re-adds a removed one`() = runTest {
        val repo = repo()
        repo.autoSave(wayne, "Wayne")
        repo.autoSave(wayne, "Someone else")
        assertEquals("Wayne", repo.contacts.first().single().name)

        repo.remove(wayne)
        repo.autoSave(wayne, "Wayne")
        assertFalse(repo.isSaved(wayne).first())

        repo.save(wayne, null) // by hand: comes back
        assertTrue(repo.isSaved(wayne).first())
    }

    @Test
    fun `nothing goes to the phone until the user turns it on and allows it`() = runTest {
        val phone = FakePhone(allowed = true)
        val repo = repo(phone = phone)
        repo.save(wayne, "Wayne")
        assertTrue(phone.onPhone.isEmpty())

        repo.setPhoneSync(true) // copies what's already saved
        assertEquals(mapOf(wayne to "Wayne"), phone.onPhone)

        repo.autoSave(merv, "Merv")
        assertEquals("Merv", phone.onPhone[merv])
    }

    @Test
    fun `without the permission contacts stay in MatChat only`() = runTest {
        val phone = FakePhone(allowed = false)
        val repo = repo(phone = phone)
        repo.setPhoneSync(true)
        repo.save(wayne, "Wayne")
        assertTrue(phone.onPhone.isEmpty())
        assertTrue(repo.isSaved(wayne).first())
        assertFalse(repo.phoneSyncEnabled.first())
    }

    @Test
    fun `turning it on asks for the permission only when it isn't granted`() = runTest {
        val phone = FakePhone(allowed = false)
        val repo = repo(phone = phone)
        assertFalse(repo.turnOnPhoneSync()) // caller must ask Android
        assertFalse(repo.onPhonePermissionResult(granted = false))
        assertFalse(repo.phoneSyncEnabled.first())

        phone.allowed = true // the user allowed it in the system dialog
        assertTrue(repo.onPhonePermissionResult(granted = true))
        assertTrue(repo.phoneSyncEnabled.first())
    }

    @Test
    fun `a merge takes the phone contact's name in MatChat`() = runTest {
        val repo = repo(phone = FakePhone(allowed = true, mergedName = "Mom"))
        repo.save(wayne, "wayne_matrix")
        assertTrue(repo.mergeIntoPhoneContact("content://contacts/1", wayne))
        assertEquals("Mom", repo.contacts.first().single().name)
    }

    @Test
    fun `a failed merge changes nothing`() = runTest {
        val repo = repo(phone = FakePhone(allowed = true, mergedName = null))
        repo.save(wayne, "Wayne")
        assertFalse(repo.mergeIntoPhoneContact("content://contacts/1", wayne))
        assertEquals("Wayne", repo.contacts.first().single().name)
    }
}
