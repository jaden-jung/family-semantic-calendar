package com.familysemanticcalendar.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth

class CalendarMonthWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            render(context, appWidgetManager, widgetId, monthTitle(), loadingBody())
        }
        Thread {
            val user = NativeStore.savedUser(context)
            val body = try {
                if (user == null) {
                    "Open app and sign in first."
                } else {
                    val calendars = CalendarApi.listCalendars(user.id)
                    val events = visibleCalendarsFor(context, calendars).flatMap { CalendarApi.listEvents(it.id, user.id) }
                    monthBody(events)
                }
            } catch (error: Exception) {
                error.message ?: "Could not load calendar."
            }
            appWidgetIds.forEach { widgetId ->
                render(context, appWidgetManager, widgetId, monthTitle(), body)
            }
        }.start()
    }

    private fun monthTitle(): String {
        val today = LocalDate.now()
        return "${today.year}.${today.monthValue}"
    }

    private fun loadingBody(): String = "S  M  T  W  T  F  S\nLoading..."

    private fun monthBody(events: List<EventItem>): String {
        val month = YearMonth.now()
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val today = LocalDate.now()
        val rows = mutableListOf("S  M  T  W  T  F  S")
        repeat(6) { week ->
            val cells = mutableListOf<String>()
            repeat(7) { day ->
                val date = start.plusDays((week * 7 + day).toLong())
                val hasEvent = eventsForDate(events, date).isNotEmpty()
                val marker = when {
                    date == today -> "!"
                    hasEvent -> "*"
                    else -> " "
                }
                val number = if (date.month == month.month) date.dayOfMonth.toString().padStart(2, ' ') else "  "
                cells.add("$number$marker")
            }
            rows.add(cells.joinToString(""))
        }
        return rows.joinToString("\n")
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, title: String, body: String) {
        val views = RemoteViews(context.packageName, R.layout.widget_month).apply {
            setTextViewText(R.id.monthWidgetTitle, title)
            setTextViewText(R.id.monthWidgetBody, body)
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.monthWidgetRoot, pendingIntent)
        }
        manager.updateAppWidget(widgetId, views)
    }
}
