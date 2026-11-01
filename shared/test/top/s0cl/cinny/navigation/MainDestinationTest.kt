package top.s0cl.cinny.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainDestinationTest {
    @Test
    fun bottomNavigationContainsTheFiveM0DestinationsInCinnyOrder() {
        assertEquals(5, MainDestination.entries.size)
        assertEquals(
            listOf("首页", "私信", "空间", "收件箱", "我的"),
            MainDestination.entries.map { it.title },
        )
    }

    @Test
    fun everyDestinationHasANonBlankSummary() {
        MainDestination.entries.forEach { destination ->
            assertTrue(destination.summary.isNotBlank(), "${destination.name} summary")
        }
    }
}
