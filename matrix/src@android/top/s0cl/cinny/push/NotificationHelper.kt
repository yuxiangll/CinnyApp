package top.s0cl.cinny.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * M3：通知渠道与本地通知。
 *
 * 渠道对齐 Element 的语义：消息 / 提及与关键词 / 通话。
 * 真正的“后台到达”依赖 M3 的推送注册（HTTP pusher / UnifiedPush），
 * 这里的本地通知用于应用运行期间收到 sync / 推送后展示。
 */
class NotificationHelper(private val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "房间消息"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MENTIONS, "提及与关键词", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "被 @ 提及或命中关键词"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "通话", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "语音/视频通话邀请"
            },
        )
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun notifyMessage(
        roomId: String,
        roomName: String,
        sender: String,
        body: String,
        highlight: Boolean = false,
    ) {
        Log.i(TAG, "notifyMessage room=" + roomName + " sender=" + sender)
        if (!canNotify()) {
            Log.i(TAG, "notification skipped (permission denied)")
            return
        }
        val intent = Intent(context, Class.forName(MAIN_ACTIVITY)).apply {
            putExtra(EXTRA_ROOM_ID, roomId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            context,
            if (highlight) CHANNEL_MENTIONS else CHANNEL_MESSAGES,
        )
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(roomName)
            .setContentText("$sender: $body")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$sender: $body"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(if (highlight) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(roomId.hashCode(), notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    fun notifyCall(roomId: String, roomName: String, sender: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(roomName)
            .setContentText("$sender 发起了通话")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(("call:" + roomId).hashCode(), notification)
        }.onFailure { Log.w(TAG, "call notify failed", it) }
    }

    companion object {
        private const val TAG = "NotificationHelper"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MENTIONS = "mentions"
        const val CHANNEL_CALLS = "calls"
        const val EXTRA_ROOM_ID = "top.s0cl.cinny.extra.ROOM_ID"
        private const val MAIN_ACTIVITY = "top.s0cl.cinny.MainActivity"
    }
}
