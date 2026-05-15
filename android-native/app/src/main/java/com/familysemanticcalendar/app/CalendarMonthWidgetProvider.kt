package com.familysemanticcalendar.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth

class CalendarMonthWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            render(context, appWidgetManager, widgetId, monthTitle(), emptyList(), isLoading = true)
        }
        Thread {
            val user = NativeStore.savedUser(context)
            val result = try {
                if (user == null) {
                    WidgetMonthData(emptyList(), "앱에서 로그인 필요")
                } else {
                    val calendars = CalendarApi.listCalendars(user.id)
                    val events = visibleCalendarsFor(context, calendars).flatMap { CalendarApi.listEvents(it.id, user.id) }
                    WidgetMonthData(events, null)
                }
            } catch (error: Exception) {
                WidgetMonthData(emptyList(), "달력을 불러오지 못함")
            }
            appWidgetIds.forEach { widgetId ->
                render(context, appWidgetManager, widgetId, monthTitle(), result.events, isLoading = false, status = result.status)
            }
        }.start()
    }

    private fun monthTitle(): String {
        val today = LocalDate.now()
        return "${today.year}.${today.monthValue}"
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, title: String, events: List<EventItem>, isLoading: Boolean, status: String? = null) {
        val views = RemoteViews(context.packageName, R.layout.widget_month).apply {
            setTextViewText(R.id.monthWidgetTitle, if (isLoading) "$title 불러오는 중" else title)
            fillMonthCells(this, events, isLoading, status)
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.monthWidgetRoot, pendingIntent)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun fillMonthCells(views: RemoteViews, events: List<EventItem>, isLoading: Boolean, status: String?) {
        val month = YearMonth.now()
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val today = LocalDate.now()
        cellIds.forEachIndexed { index, cellId ->
            val date = start.plusDays(index.toLong())
            val dayEvents = if (isLoading) emptyList() else eventsForDate(events, date)
            val holiday = holidayName(date)
            val isCurrentMonth = date.month == month.month
            val isSunday = date.dayOfWeek.value == 7
            val isSaturday = date.dayOfWeek.value == 6
            val textLines = mutableListOf<String>()
            textLines.add(date.dayOfMonth.toString())
            if (holiday != null) textLines.add(holiday)
            if (dayEvents.isNotEmpty()) {
                val colorCount = dayEvents.map { it.calendarId }.distinct().take(3).size
                val dots = "●".repeat(colorCount)
                textLines.add("$dots ${dayEvents.first().title}")
            }
            views.setTextViewText(cellId, textLines.joinToString("\n"))
            views.setTextColor(
                cellId,
                when {
                    !isCurrentMonth -> Color.rgb(148, 163, 184)
                    holiday != null || isSunday -> Color.rgb(220, 38, 38)
                    isSaturday -> Color.rgb(37, 99, 235)
                    else -> Color.rgb(15, 23, 42)
                }
            )
            val bgColor = when {
                date == today -> Color.rgb(204, 251, 241)
                dayEvents.isNotEmpty() -> softColor(calendarColor(dayEvents.first().calendarId))
                !isCurrentMonth -> Color.rgb(248, 250, 252)
                else -> Color.WHITE
            }
            views.setInt(cellId, "setBackgroundColor", bgColor)
        }
        if (status != null) {
            views.setTextViewText(R.id.mw_d0, status)
            views.setTextColor(R.id.mw_d0, Color.rgb(71, 85, 105))
            views.setInt(R.id.mw_d0, "setBackgroundColor", Color.rgb(248, 250, 252))
        }
    }

    private fun softColor(color: Int): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.rgb(
            ((red * 0.16f) + (255 * 0.84f)).toInt(),
            ((green * 0.16f) + (255 * 0.84f)).toInt(),
            ((blue * 0.16f) + (255 * 0.84f)).toInt(),
        )
    }

    private fun calendarColor(calendarId: String): Int {
        val index = kotlin.math.abs(calendarId.hashCode()) % calendarPalette.size
        return calendarPalette[index]
    }

    private companion object {
        val calendarPalette = listOf(
            0xFF0F766E.toInt(),
            0xFF2563EB.toInt(),
            0xFFC2410C.toInt(),
            0xFF7C3AED.toInt(),
            0xFFBE123C.toInt(),
            0xFF15803D.toInt(),
        )
        val cellIds = intArrayOf(
            R.id.mw_d0, R.id.mw_d1, R.id.mw_d2, R.id.mw_d3, R.id.mw_d4, R.id.mw_d5, R.id.mw_d6,
            R.id.mw_d7, R.id.mw_d8, R.id.mw_d9, R.id.mw_d10, R.id.mw_d11, R.id.mw_d12, R.id.mw_d13,
            R.id.mw_d14, R.id.mw_d15, R.id.mw_d16, R.id.mw_d17, R.id.mw_d18, R.id.mw_d19, R.id.mw_d20,
            R.id.mw_d21, R.id.mw_d22, R.id.mw_d23, R.id.mw_d24, R.id.mw_d25, R.id.mw_d26, R.id.mw_d27,
            R.id.mw_d28, R.id.mw_d29, R.id.mw_d30, R.id.mw_d31, R.id.mw_d32, R.id.mw_d33, R.id.mw_d34,
            R.id.mw_d35, R.id.mw_d36, R.id.mw_d37, R.id.mw_d38, R.id.mw_d39, R.id.mw_d40, R.id.mw_d41,
        )
    }

    private data class WidgetMonthData(val events: List<EventItem>, val status: String?)
}
