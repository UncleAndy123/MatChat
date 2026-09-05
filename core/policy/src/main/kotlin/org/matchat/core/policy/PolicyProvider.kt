package org.matchat.core.policy

import kotlinx.coroutines.flow.StateFlow

/**
 * Live source of [Policy]. It is a Flow, not a value read once, because the EMM
 * can push a new bundle at any time and the app re-reads it on
 * ACTION_APPLICATION_RESTRICTIONS_CHANGED without a restart. A screen that caches
 * an allow/deny decision across a policy change is a bug (AGENTS.md "Policy rules").
 */
interface PolicyProvider {
    val policy: StateFlow<Policy>
}
