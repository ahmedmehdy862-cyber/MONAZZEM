package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The HTTP-error mapping that keeps a wrong passphrase from looking like a lost connection:
 * 401/403/400 (the server refused the secret or the profile) is REJECTED, everything else
 * (timeout, 429, 503) stays NETWORK_ERROR and is retried. See SyncAccount.resultFor.
 */
class SyncAccountResultTest {

    @Test fun authFailuresAreRejectedNotNetworkErrors() {
        assertEquals(
            SyncAccount.Result.REJECTED,
            SyncAccount.resultFor(HttpStatusException(401, """{"error":"invalid_credentials"}"""))
        )
        assertEquals(
            SyncAccount.Result.REJECTED,
            SyncAccount.resultFor(HttpStatusException(403, """{"error":"profile_unknown"}"""))
        )
        assertEquals(
            SyncAccount.Result.REJECTED,
            SyncAccount.resultFor(HttpStatusException(400, """{"error":"invalid_passphrase"}"""))
        )
    }

    @Test fun staleRevisionAndRateLimitHaveTheirOwnOutcomes() {
        assertEquals(
            SyncAccount.Result.STALE_REVISION,
            SyncAccount.resultFor(HttpStatusException(409, """{"error":"stale_revision"}"""))
        )
        assertEquals(
            SyncAccount.Result.RATE_LIMITED,
            SyncAccount.resultFor(HttpStatusException(429, """{"error":"too_many_requests"}"""))
        )
    }

    @Test fun transientFailuresStayNetworkErrors() {
        assertEquals(
            SyncAccount.Result.NETWORK_ERROR,
            SyncAccount.resultFor(HttpStatusException(503, """{"error":"sync_unavailable"}"""))
        )
        assertEquals(
            SyncAccount.Result.NETWORK_ERROR,
            SyncAccount.resultFor(java.net.SocketTimeoutException("timed out"))
        )
        assertEquals(
            SyncAccount.Result.NETWORK_ERROR,
            SyncAccount.resultFor(IllegalStateException("boom"))
        )
    }
}
