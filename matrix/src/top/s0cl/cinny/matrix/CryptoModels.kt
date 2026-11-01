package top.s0cl.cinny.matrix

/**
 * M3：E2EE 与开发者工具共享的数据模型。
 *
 * 具体的加密实现位于 Android 源集（matrix-rust-sdk），
 * 这里只保留跨平台可见的状态快照，便于 Compose UI 与单测使用。
 */
data class CryptoStatus(
    val initialized: Boolean = false,
    val phase: String = "未初始化",
    val relayPort: Int = 0,
    val deviceId: String? = null,
    val identityEd25519: String? = null,
    val identityCurve25519: String? = null,
    val verificationState: String = "未知",
    val backupState: String = "未知",
    val recoveryState: String = "未知",
    val recoveryKey: String? = null,
    val error: String? = null,
)

data class DecryptedEvent(
    val eventId: String,
    val body: String? = null,
    val msgType: String? = null,
    val isEdited: Boolean = false,
    val unableToDecrypt: Boolean = false,
    val note: String? = null,
)

data class DevEventResult(
    val eventId: String? = null,
    val message: String,
)
