package top.s0cl.cinny.matrix

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryMatrixEngineTest {
    @Test
    fun loginStartsSyncAndSplitsHomeAndDirectRooms() = runBlocking {
        val engine = InMemoryMatrixEngine()
        val result = engine.login("matrix.org", Credentials("alice", "secret"))
        assertTrue(result.isSuccess)
        assertEquals(SyncState.Syncing, engine.syncState.value)
        val rooms = engine.rooms().first()
        assertEquals(1, rooms.count { !it.isDirect })
        assertEquals(1, rooms.count { it.isDirect })
    }

    @Test
    fun sendAppendsMessageToTimeline() = runBlocking {
        val engine = InMemoryMatrixEngine()
        engine.login("matrix.org", Credentials("alice", "secret"))
        val before = engine.timeline("!cinny:example.org").first().items.size
        engine.send("!cinny:example.org", MessageDraft("hello M1"))
        val after = engine.timeline("!cinny:example.org").first().items
        assertEquals(before + 1, after.size)
        assertEquals("hello M1", after.last().body)
    }

    @Test
    fun m2SearchEditReactionAndSpacesWork() = runBlocking {
        val engine = InMemoryMatrixEngine()
        engine.login("matrix.org", Credentials("alice", "secret"))
        val roomId = "!cinny:example.org"
        val first = engine.timeline(roomId).first().items.first()

        val search = engine.search("preview", null).getOrThrow()
        assertTrue(search.isNotEmpty())

        assertTrue(engine.edit(roomId, first.eventId, "edited").isSuccess)
        assertEquals("edited", engine.timeline(roomId).first().items.first().body)

        assertTrue(engine.toggleReaction(roomId, first.eventId, "👍").isSuccess)
        assertTrue(engine.timeline(roomId).first().items.first().reactions.isNotEmpty())

        assertTrue(engine.spaces().first().isNotEmpty())
    }

    @Test
    fun logoutClearsSession() = runBlocking {
        val engine = InMemoryMatrixEngine()
        engine.login("matrix.org", Credentials("alice", "secret"))
        engine.logout()
        assertTrue(engine.session.value is SessionState.LoggedOut)
        assertEquals(SyncState.Idle, engine.syncState.value)
    }
}
