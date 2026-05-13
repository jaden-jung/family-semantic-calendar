package com.familysemanticcalendar.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate

class CalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            render(context, appWidgetManager, widgetId, "오늘 일정", "불러오는 중...")
        }
        Thread {
            val user = NativeStore.savedUser(context)
            val body = try {
                if (user == null) {
                    "앱에서 먼저 로그인해 주세요."
                } else {
                    val calendars = CalendarApi.listCalendars(user.id)
                    val todayEvents = calendars.flatMap { CalendarApi.listEvents(it.id, user.id) }
                        .let { eventsForDate(it, LocalDate.now()) }
                    if (todayEvents.isEmpty()) {
                        "오늘 등록된 일정이 없습니다."
                    } else {
                        todayEvents.take(5).joinToString("\n") { event ->
                            "%02d:%02d %s".format(event.startsAt.hour, event.startsAt.minute, event.title)
                        }
                    }
                }
            } catch (error: Exception) {
                error.message ?: "일정을 불러오지 못했습니다."
            }
            appWidgetIds.forEach { widgetId ->
                render(context, appWidgetManager, widgetId, "오늘 일정", body)
            }
        }.start()
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, title: String, body: String) {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar).apply {
            setTextViewText(R.id.widgetTitle, title)
            setTextViewText(R.id.widgetBody, body)
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
        }
        manager.updateAppWidget(widgetId, views)
    }
}
