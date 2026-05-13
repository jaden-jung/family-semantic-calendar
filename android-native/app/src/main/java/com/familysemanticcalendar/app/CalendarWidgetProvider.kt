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
            render(context, appWidgetManager, widgetId, todayTitle(), "불러오는 중...")
        }
        Thread {
            val user = NativeStore.savedUser(context)
            val bodyByWidget = try {
                if (user == null) {
                    appWidgetIds.associateWith { "앱에서 먼저 로그인해 주세요." }
                } else {
                    val calendars = CalendarApi.listCalendars(user.id)
                    val members = CalendarApi.listUsers(user.id)
                    val events = calendars.flatMap { CalendarApi.listEvents(it.id, user.id) }
                    appWidgetIds.associateWith { widgetId ->
                        val options = appWidgetManager.getAppWidgetOptions(widgetId)
                        val maxItems = if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) >= 180) 8 else 4
                        upcomingBody(events, members, maxItems)
                    }
                }
            } catch (error: Exception) {
                appWidgetIds.associateWith { error.message ?: "일정을 불러오지 못했습니다." }
            }
            appWidgetIds.forEach { widgetId ->
                render(context, appWidgetManager, widgetId, todayTitle(), bodyByWidget[widgetId].orEmpty())
            }
        }.start()
    }

    private fun todayTitle(): String {
        val today = LocalDate.now()
        val holiday = holidayName(today)?.let { " · $it" }.orEmpty()
        return "오늘 일정 · ${today.monthValue}/${today.dayOfMonth}$holiday"
    }

    private fun upcomingBody(events: List<EventItem>, members: List<User>, maxItems: Int): String {
        val today = LocalDate.now()
        val rows = mutableListOf<String>()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            val dayEvents = eventsForDate(events, date)
            dayEvents.forEach { event ->
                val prefix = when (offset) {
                    0 -> "%02d:%02d".format(event.startsAt.hour, event.startsAt.minute)
                    1 -> "내일"
                    else -> "${date.monthValue}/${date.dayOfMonth}"
                }
                rows.add("$prefix [${ownerName(members, event.createdBy)}] ${event.title}")
            }
            if (rows.size >= maxItems) break
        }
        return if (rows.isEmpty()) {
            val holiday = holidayName(today)
            if (holiday == null) "오늘 등록된 일정이 없습니다." else "$holiday 외 등록된 일정이 없습니다."
        } else {
            rows.take(maxItems).joinToString("\n")
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, title: String, body: String) {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar).apply {
            setTextViewText(R.id.widgetTitle, title)
            setTextViewText(R.id.widgetBody, body)
            setInt(R.id.widgetBody, "setMaxLines", body.lineSequence().count().coerceIn(3, 8))
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
        }
        manager.updateAppWidget(widgetId, views)
    }
}
