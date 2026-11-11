package com.siamaehwa.nimstudio.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.siamaehwa.nimstudio.MainActivity
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.R

object Notifier {
    private const val CHANNEL_ID = "nim_agent_alerts"
    private const val ID_QUESTION = 1001
    private const val ID_PERMISSION = 1002
    private const val ID_DONE = 1003

    fun question(context: Context, text: String) =
        notify(context, ID_QUESTION, "에이전트 질문", text)

    fun permission(context: Context, text: String) =
        notify(context, ID_PERMISSION, "권한 확인 필요", text)

    fun completed(context: Context, text: String) =
        notify(context, ID_DONE, "에이전트 작업 완료", text)

    fun clear(context: Context, id: Int = 0) {
        val manager = NotificationManagerCompat.from(context)
        when (id) {
            0 -> {
                runCatching { manager.cancel(ID_QUESTION) }
                runCatching { manager.cancel(ID_PERMISSION) }
                runCatching { manager.cancel(ID_DONE) }
            }

            else -> runCatching { manager.cancel(id) }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if ((context.applicationContext as? NimStudioApp)?.isInForeground == true) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nim)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "에이전트 알림",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "에이전트가 질문·권한 확인·작업 완료를 알립니다."
        }
        manager.createNotificationChannel(channel)
    }
}
