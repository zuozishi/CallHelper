package com.zuozishi.callhelper

import com.zuozishi.callhelper.domain.isDirectoryUpdateOverdue
import org.junit.Assert.*
import org.junit.Test

class DirectoryFreshnessTest {
    private val week = 7L * 24 * 60 * 60 * 1000
    private val updated = 1_800_000_000_000L

    @Test fun promptsOnlyAfterSevenFullDays() {
        assertFalse(isDirectoryUpdateOverdue(updated, updated + week - 1))
        assertFalse(isDirectoryUpdateOverdue(updated, updated + week))
        assertTrue(isDirectoryUpdateOverdue(updated, updated + week + 1))
    }

    @Test fun missingOrFutureTimestampDoesNotClaimStaleData() {
        assertFalse(isDirectoryUpdateOverdue(0, updated))
        assertFalse(isDirectoryUpdateOverdue(-1, updated))
        assertFalse(isDirectoryUpdateOverdue(updated, updated - 1))
    }

    @Test fun successfulRefreshClearsOverdueStatus() {
        val now = updated + week + 1
        assertTrue(isDirectoryUpdateOverdue(updated, now))
        assertFalse(isDirectoryUpdateOverdue(now, now))
    }
}
