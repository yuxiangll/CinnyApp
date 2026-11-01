package top.s0cl.cinny.matrix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatrixContractTest {
    @Test
    fun syncStateCoversTheM0Contract() {
        assertEquals(
            listOf("Idle", "Connecting", "Syncing", "Offline", "Error"),
            SyncState.entries.map { it.name },
        )
    }

    @Test
    fun sessionCarriesTheSameFieldsAsCinnyFallbackSession() {
        val session = Session(
            homeserverUrl = "https://matrix.org",
            userId = "@alice:matrix.org",
            deviceId = "DEVICE",
            accessToken = "token",
        )
        assertTrue(session.refreshToken == null)
        assertEquals("@alice:matrix.org", session.userId)
    }
}
