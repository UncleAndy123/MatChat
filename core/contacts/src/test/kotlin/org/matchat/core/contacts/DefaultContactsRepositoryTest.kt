package org.matchat.core.contacts

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import org.matchat.core.policy.Policy
import org.matchat.core.policy.PolicyProvider

class DefaultContactsRepositoryTest {

    private class FakePolicy(policy: Policy) : PolicyProvider {
        override val policy: StateFlow<Policy> = MutableStateFlow(policy)
    }

    private class FakeLocal(private val entries: List<Contact>) : LocalContactsStore {
        override val local: Flow<List<Contact>> = flowOf(entries)
        override val recents: Flow<List<Contact>> = flowOf(entries)
        override suspend fun addRecent(address: UserId) = Unit
    }

    @Test
    fun `admin first, deduplicated by address`() = runTest {
        val admin = Contact(UserId("@wayne:example.org"), "Wayne", Contact.Source.ADMIN)
        val localDup = Contact(UserId("@wayne:example.org"), null, Contact.Source.LOCAL)
        val localOnly = Contact(UserId("@merv:carpathianserver.org"), null, Contact.Source.LOCAL)

        val repo = DefaultContactsRepository(
            policyProvider = FakePolicy(Policy.UNMANAGED.copy(adminContacts = listOf(admin))),
            localStore = FakeLocal(listOf(localDup, localOnly)),
        )

        repo.contacts.test {
            val merged = awaitItem()
            assertEquals(listOf(admin, localOnly), merged)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
