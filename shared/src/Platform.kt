package top.s0cl.cinny

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
