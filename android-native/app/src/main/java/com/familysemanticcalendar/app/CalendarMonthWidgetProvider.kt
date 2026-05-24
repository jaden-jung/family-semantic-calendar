package com.familysemanticcalendar.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
            } catch (_: Exception) {
                WidgetMonthData(emptyList(), "달력을 불러오지 못함")
            }
            appWidgetIds.forEach { widgetId ->
                render(context, appWidgetManager, widgetId, monthTitle(), result.events, isLoading = false, status = result.status)
            }
        }.start()
    }

    private fun monthTitle(): String {
        val today = LocalDate.now()
        return "${today.year}년 ${today.monthValue}월"
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        title: String,
        events: List<EventItem>,
        isLoading: Boolean,
        status: String? = null,
    ) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_month).apply {
            setTextViewText(R.id.monthWidgetTitle, if (isLoading) "$title 불러오는 중" else title)
            setOnClickPendingIntent(R.id.monthWidgetRoot, pendingIntent)
            fillMonthCells(this, events, isLoading, status, pendingIntent)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun fillMonthCells(
        views: RemoteViews,
        events: List<EventItem>,
        isLoading: Boolean,
        status: String?,
        pendingIntent: PendingIntent,
    ) {
        val month = YearMonth.now()
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val today = LocalDate.now()
        val eventCache = mutableMapOf<LocalDate, List<EventItem?>>()
        val slotCache = mutableMapOf<LocalDate, Map<String, Int>>()

        cellIds.forEachIndexed { index, cellId ->
            val date = start.plusDays(index.toLong())
            val dayEvents = if (isLoading) emptyList() else widgetEventsForDate(events, date, eventCache, slotCache)
            val holiday = holidayName(date)
            val currentMonth = date.month == month.month
            val sunday = date.dayOfWeek.value == 7
            val saturday = date.dayOfWeek.value == 6
            val dateColor = when {
                !currentMonth -> Color.rgb(148, 163, 184)
                holiday != null || sunday -> Color.rgb(220, 38, 38)
                saturday -> Color.rgb(37, 99, 235)
                else -> Color.rgb(15, 23, 42)
            }

            views.setTextViewText(
                cellId,
                widgetCellText(
                    date = date,
                    holiday = holiday,
                    dayEvents = dayEvents,
                    maxRows = if (holiday == null) 7 else 6,
                    dateColor = dateColor,
                    defaultColor = if (currentMonth) Color.rgb(15, 23, 42) else Color.rgb(148, 163, 184),
                ),
            )
            views.setTextColor(cellId, if (currentMonth) Color.rgb(15, 23, 42) else Color.rgb(148, 163, 184))
            views.setInt(
                cellId,
                "setBackgroundColor",
                when {
                    date == today -> Color.rgb(204, 251, 241)
                    !currentMonth -> Color.rgb(248, 250, 252)
                    else -> Color.WHITE
                },
            )
            views.setOnClickPendingIntent(cellId, pendingIntent)
        }

        if (status != null) {
            views.setTextViewText(R.id.mw_d0, status)
            views.setTextColor(R.id.mw_d0, Color.rgb(71, 85, 105))
            views.setInt(R.id.mw_d0, "setBackgroundColor", Color.rgb(248, 250, 252))
        }
    }

    private fun widgetCellText(
        date: LocalDate,
        holiday: String?,
        dayEvents: List<EventItem?>,
        maxRows: Int,
        dateColor: Int,
        defaultColor: Int,
    ): SpannableString {
        val realEvents = dayEvents.filterNotNull()
        val visibleEvents = if (realEvents.size > maxRows) {
            dayEvents.take((maxRows - 1).coerceAtLeast(0))
        } else {
            dayEvents.take(maxRows)
        }
        val hiddenCount = (realEvents.size - visibleEvents.filterNotNull().size).coerceAtLeast(0)
        val lines = mutableListOf<WidgetLine>()
        lines.add(WidgetLine(date.dayOfMonth.toString(), dateColor, null))
        if (holiday != null) lines.add(WidgetLine(holiday, Color.rgb(220, 38, 38), null))
        visibleEvents.forEach { event ->
            if (event == null) {
                lines.add(WidgetLine(" ", defaultColor, null))
            } else {
                val multiDay = event.isMultiDay()
                val text = if (multiDay) {
                    val segmentStart = event.startsAt.toLocalDate() == date || date.dayOfWeek.value == 7
                    if (segmentStart) event.title.take(8) else " "
                } else {
                    event.title.take(8)
                }
                lines.add(WidgetLine(text, defaultColor, softColor(calendarColor(event.calendarId))))
            }
        }
        if (hiddenCount > 0) lines.add(WidgetLine("+$hiddenCount", Color.rgb(71, 85, 105), null))

        val raw = lines.joinToString("\n") { it.text }
        val spannable = SpannableString(raw)
        var offset = 0
        lines.forEach { line ->
            val end = offset + line.text.length
            if (end > offset) {
                spannable.setSpan(ForegroundColorSpan(line.color), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (line.backgroundColor != null && line.text.isNotBlank()) {
                    spannable.setSpan(BackgroundColorSpan(line.backgroundColor), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            offset = end + 1
        }
        return spannable
    }

    private fun widgetEventsForDate(
        events: List<EventItem>,
        date: LocalDate,
        eventCache: MutableMap<LocalDate, List<EventItem?>>,
        slotCache: MutableMap<LocalDate, Map<String, Int>>,
    ): List<EventItem?> {
        eventCache[date]?.let { return it }
        val weekStart = date.minusDays(date.dayOfWeek.value % 7L)
        val slotByEventId = slotCache.getOrPut(weekStart) { widgetMultiDaySlotsForWeek(events, weekStart) }
        val dayEvents = eventsForDate(events, date)
        val activeMultiDayEvents = dayEvents.filter { it.isMultiDay() }
        val lastActiveSlot = activeMultiDayEvents.mapNotNull { slotByEventId[it.id] }.maxOrNull() ?: -1
        val slots = MutableList<EventItem?>(lastActiveSlot + 1) { null }
        activeMultiDayEvents.forEach { event ->
            val slot = slotByEventId[event.id]
            if (slot != null && slot in slots.indices) slots[slot] = event
        }
        val singleDayEvents = dayEvents.filterNot { it.isMultiDay() }.toMutableList()
        return (slots.map { it ?: singleDayEvents.removeFirstOrNull() } + singleDayEvents).also {
            eventCache[date] = it
        }
    }

    private fun widgetMultiDaySlotsForWeek(events: List<EventItem>, weekStart: LocalDate): Map<String, Int> {
        val weekDates = (0..6).map { weekStart.plusDays(it.toLong()) }
        val weekMultiDayEvents = weekDates
            .flatMap { day -> eventsForDate(events, day).filter { it.isMultiDay() } }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<EventItem> { it.startsAt.toLocalDate() }
                    .thenBy { it.startsAt.toLocalTime() }
                    .thenBy { it.title }
            )
        val slotEnds = mutableListOf<LocalDate>()
        val slotByEventId = mutableMapOf<String, Int>()
        weekMultiDayEvents.forEach { event ->
            val eventStart = event.startsAt.toLocalDate()
            val eventEnd = event.endsAt?.toLocalDate() ?: eventStart
            val segmentStart = maxOf(eventStart, weekStart)
            val segmentEnd = minOf(eventEnd, weekStart.plusDays(6))
            val reusableSlot = slotEnds.indexOfFirst { it.isBefore(segmentStart) }
            val slot = if (reusableSlot >= 0) reusableSlot else slotEnds.size.also { slotEnds.add(segmentEnd) }
            slotEnds[slot] = segmentEnd
            slotByEventId[event.id] = slot
        }
        return slotByEventId
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
    private data class WidgetLine(val text: String, val color: Int, val backgroundColor: Int?)
}
