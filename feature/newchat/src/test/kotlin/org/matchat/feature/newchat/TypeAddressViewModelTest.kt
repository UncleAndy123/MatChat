package org.matchat.feature.newchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.model.Contact
import org.matchat.core.model.ErrorText
import org.matchat.core.model.Profile
import org.matchat.core.model.UserId
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class TypeAddressViewModelTest {

    private val session = FakeMatrixSession()

    private val noContacts = object : ContactsRepository {
        override val contacts: Flow<List<Contact>> = flowOf(emptyList())
        override val recents: Flow<List<Contact>> = flowOf(emptyList())
        override suspend fun recordRecent(address: UserId) = Unit
    }

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun subject(policy: Policy = Policy.UNMANAGED) =
        TypeAddressViewModel(session, FakePolicyProvider(policy), noContacts)

    @Test
    fun `malformed address is rejected before any network step`() {
        val vm = subject()
        vm.onAction(TypeAddressAction.Continue("not-an-address"))
        assertEquals(ErrorText.Key.MALFORMED_ADDRESS, vm.state.value.error?.key)
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun `blocked domain is named and goes no further`() {
        val policy = Policy(isManaged = true, allowedDomains = listOf("example.org"))
        val vm = subject(policy)
        vm.onAction(TypeAddressAction.Continue("@x:elsewhere.net"))
        assertEquals("elsewhere.net", vm.state.value.blockedDomain)
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun `valid allowed address resolves to a confirm step`() = runTest {
        session.profileResult = { Result.success(Profile(it, "Wayne Zimmerman")) }
        val vm = subject()
        vm.onAction(TypeAddressAction.Continue("@wayne:example.org"))
        testScheduler.advanceUntilIdle()
        val confirm = vm.state.value.confirm
        assertNotNull(confirm)
        assertEquals("Wayne Zimmerman", confirm?.name)
    }

    @Test
    fun `unresolved profile still offers start anyway`() = runTest {
        session.profileResult = { Result.success(Profile(it, null)) }
        val vm = subject()
        vm.onAction(TypeAddressAction.Continue("@ghost:example.org"))
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.state.value.confirm?.unresolved)
    }
}
