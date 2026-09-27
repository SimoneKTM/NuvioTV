package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonReconcilePlanTest {

    @Test
    fun `merge keeps locally installed addons missing from remote`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://a", "https://b"),
            remoteUrls = listOf("https://a"),
            mode = AddonReconcileMode.MERGE_KEEP_LOCAL
        )
        assertEquals(listOf("https://a", "https://b"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }

    @Test
    fun `merge appends addons that only exist remotely`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://a"),
            remoteUrls = listOf("https://a", "https://c"),
            mode = AddonReconcileMode.MERGE_KEEP_LOCAL
        )
        assertEquals(listOf("https://a", "https://c"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }

    @Test
    fun `adopt removes local addons missing from remote`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://a", "https://b"),
            remoteUrls = listOf("https://a"),
            mode = AddonReconcileMode.ADOPT_REMOTE
        )
        assertEquals(listOf("https://a"), plan.finalUrls)
        assertEquals(listOf("https://b"), plan.removedUrls)
    }

    @Test
    fun `adopt with empty remote never wipes the local list`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://a", "https://b"),
            remoteUrls = emptyList(),
            mode = AddonReconcileMode.ADOPT_REMOTE
        )
        assertEquals(listOf("https://a", "https://b"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }

    @Test
    fun `merge with empty remote keeps the local list untouched`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://a"),
            remoteUrls = emptyList(),
            mode = AddonReconcileMode.MERGE_KEEP_LOCAL
        )
        assertEquals(listOf("https://a"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }

    @Test
    fun `remote url casing does not duplicate or drop addons`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://A"),
            remoteUrls = listOf("https://a"),
            mode = AddonReconcileMode.MERGE_KEEP_LOCAL
        )
        assertEquals(listOf("https://A"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }

    @Test
    fun `blank remote entries are ignored`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://a"),
            remoteUrls = listOf("", "  ", "https://a"),
            mode = AddonReconcileMode.ADOPT_REMOTE
        )
        assertEquals(listOf("https://a"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }

    @Test
    fun `duplicate remote entries are collapsed preserving first occurrence`() {
        val plan = planAddonReconcile(
            localUrls = emptyList(),
            remoteUrls = listOf("https://a", "https://A", "https://b"),
            mode = AddonReconcileMode.ADOPT_REMOTE
        )
        assertEquals(listOf("https://a", "https://b"), plan.finalUrls)
    }

    @Test
    fun `adopt keeps the local canonical form for urls present in both lists`() {
        val plan = planAddonReconcile(
            localUrls = listOf("https://Local/Form"),
            remoteUrls = listOf("https://local/form"),
            mode = AddonReconcileMode.ADOPT_REMOTE
        )
        assertEquals(listOf("https://Local/Form"), plan.finalUrls)
        assertTrue(plan.removedUrls.isEmpty())
    }
}
