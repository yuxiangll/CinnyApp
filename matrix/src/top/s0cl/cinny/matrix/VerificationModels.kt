package top.s0cl.cinny.matrix

/**
 * M3：SAS 设备验证的 UI 状态。
 *
 * rust-sdk 的 SessionVerificationController 是回调式的，这里把它折叠成
 * 一个可被 Compose 直接消费的状态快照。
 */
data class VerificationUiState(
    val active: Boolean = false,
    val stage: String = "空闲",
    val emojis: List<String> = emptyList(),
    val decimals: List<Int> = emptyList(),
    val requestFrom: String? = null,
    val canAccept: Boolean = false,
    val canApprove: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)
