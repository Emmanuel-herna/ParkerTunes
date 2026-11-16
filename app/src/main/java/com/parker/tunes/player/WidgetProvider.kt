package com.parker.tunes.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.parker.tunes.MainActivity

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS -> {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val future = MediaController.Builder(context, token).buildAsync()
                future.addListener({
                    runCatching {
                        val controller = future.get()
                        when (intent.action) {
                            ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                            ACTION_NEXT -> controller.seekToNextMediaItem()
                            ACTION_PREVIOUS -> controller.seekToPreviousMediaItem()
                        }
                        MediaController.releaseFuture(future)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.parker.tunes.widget.TOGGLE"
        const val ACTION_NEXT = "com.parker.tunes.widget.NEXT"
        const val ACTION_PREVIOUS = "com.parker.tunes.widget.PREVIOUS"

        fun update(context: Context, player: androidx.media3.common.Player?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, com.parker.tunes.R.layout.widget_now_playing)
            val item = player?.currentMediaItem
            views.setTextViewText(com.parker.tunes.R.id.widget_title,
                item?.mediaMetadata?.title?.toString() ?: "ParkerTunes")
            views.setTextViewText(com.parker.tunes.R.id.widget_artist,
                item?.mediaMetadata?.artist?.toString() ?: "Nothing playing")
            views.setImageViewResource(com.parker.tunes.R.id.widget_play_pause,
                if (player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)

            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(com.parker.tunes.R.id.widget_title, open)
            views.setOnClickPendingIntent(com.parker.tunes.R.id.widget_artist, open)
            views.setOnClickPendingIntent(com.parker.tunes.widgetIdRoot(), open)
            views.setOnClickPendingIntent(com.parker.tunes.R.id.widget_play_pause, broadcast(context, ACTION_TOGGLE, 1))
            views.setOnClickPendingIntent(com.parker.tunes.R.id.widget_next, broadcast(context, ACTION_NEXT, 2))
            views.setOnClickPendingIntent(com.parker.tunes.R.id.widget_previous, broadcast(context, ACTION_PREVIOUS, 3))
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(context, requestCode, Intent(context, WidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

private fun com.parker.tunes.widgetIdRoot(): Int = com.parker.tunes.R.id.widget_title