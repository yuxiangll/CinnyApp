package top.s0cl.cinny

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformIosTest {
    @Test
    fun `platform name is reported`() {
        assertTrue(getPlatform().name.isNotBlank())
    }
}
