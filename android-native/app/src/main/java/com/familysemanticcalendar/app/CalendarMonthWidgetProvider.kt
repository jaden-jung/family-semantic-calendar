package com.familysemanticcalendar.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth

class CalendarMonthWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            render(context, appWidgetManager, widgetId, monthTitle(), emptyList(), isLoading = true)
        }
        Thread {
            val session = NativeStore.savedSession(context)
            val user = session?.user
            val result = try {
                if (user == null) {
                    WidgetMonthData(emptyList(), "로그인이 필요합니다")
                } else {
                    CalendarApi.accessToken = session!!.accessToken
                    val calendars = CalendarApi.listCalendars(user.id)
                    val month = YearMonth.now()
                    val first = month.atDay(1)
                    val gridStart = first.minusDays((first.dayOfWeek.value % 7).toLong())
                    val gridEnd = gridStart.plusDays(42)
                    val events = visibleCalendarsFor(context, calendars).flatMap {
                        CalendarApi.listEvents(it.id, user.id, gridEnd.atStartOfDay(), gridStart.atStartOfDay())
                    }
                    WidgetMonthData(events, null)
                }
            } catch (error: Exception) {
                Log.e("CalendarMonthWidget", "Failed to load calendar widget", error)
                WidgetMonthData(emptyList(), "위젯 오류: ${error.javaClass.simpleName}")
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
        val bitmap = drawMonthBitmap(if (isLoading) "$title 불러오는 중" else title, events, status)
        val views = RemoteViews(context.packageName, R.layout.widget_month).apply {
            setImageViewBitmap(R.id.monthWidgetImage, bitmap)
            setOnClickPendingIntent(R.id.monthWidgetRoot, pendingIntent)
            setOnClickPendingIntent(R.id.monthWidgetImage, pendingIntent)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun drawMonthBitmap(title: String, events: List<EventItem>, status: String?): Bitmap {
        val width = 900
        val height = 1320
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val month = YearMonth.now()
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        val today = LocalDate.now()
        val titlePaint = textPaint(Color.rgb(15, 118, 110), 40f, bold = true)
        val weekdayPaint = textPaint(Color.rgb(100, 116, 139), 30f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val datePaint = textPaint(Color.rgb(15, 23, 42), 26f, bold = false)
        val holidayPaint = textPaint(Color.rgb(220, 38, 38), 25f, bold = false)
        val eventPaint = textPaint(Color.rgb(15, 23, 42), 24f, bold = false)
        val hiddenPaint = textPaint(Color.rgb(71, 85, 105), 24f, bold = true)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawText(title, 0f, 44f, titlePaint)

        val weekdayTop = 70f
        val gridTop = 112f
        val cellWidth = width / 7f
        val cellHeight = (height - gridTop) / 6f
        val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")
        weekdays.forEachIndexed { index, label ->
            weekdayPaint.color = when (index) {
                0 -> Color.rgb(220, 38, 38)
                6 -> Color.rgb(37, 99, 235)
                else -> Color.rgb(100, 116, 139)
            }
            canvas.drawText(label, cellWidth * index + cellWidth / 2f, weekdayTop + 27f, weekdayPaint)
        }

        repeat(6) { row ->
            repeat(7) { col ->
                val date = start.plusDays((row * 7 + col).toLong())
                val left = col * cellWidth
                val top = gridTop + row * cellHeight
                fillPaint.color = when {
                    date == today -> Color.rgb(204, 251, 241)
                    date.month != month.month -> Color.rgb(248, 250, 252)
                    else -> Color.WHITE
                }
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, fillPaint)
            }
        }

        drawWeekEvents(canvas, events, start, month, gridTop, cellWidth, cellHeight, eventPaint, hiddenPaint, fillPaint)

        repeat(7) { col ->
            val x = col * cellWidth
            canvas.drawLine(x, gridTop, x, height.toFloat(), linePaint)
        }
        canvas.drawLine(width.toFloat(), gridTop, width.toFloat(), height.toFloat(), linePaint)
        repeat(7) { row ->
            val y = gridTop + row * cellHeight
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }

        repeat(6) { row ->
            repeat(7) { col ->
                val date = start.plusDays((row * 7 + col).toLong())
                val left = col * cellWidth
                val top = gridTop + row * cellHeight
                val holiday = holidayName(date)
                val currentMonth = date.month == month.month
                datePaint.color = when {
                    !currentMonth -> Color.rgb(148, 163, 184)
                    holiday != null || date.dayOfWeek.value == 7 -> Color.rgb(220, 38, 38)
                    date.dayOfWeek.value == 6 -> Color.rgb(37, 99, 235)
                    else -> Color.rgb(15, 23, 42)
                }
                canvas.drawText(date.dayOfMonth.toString(), left + 7f, top + 28f, datePaint)
                if (holiday != null) {
                    canvas.drawText(ellipsize(holiday, holidayPaint, cellWidth - 12f), left + 7f, top + 56f, holidayPaint)
                }
            }
        }

        if (status != null) {
            fillPaint.color = Color.argb(220, 248, 250, 252)
            canvas.drawRect(0f, gridTop, cellWidth, gridTop + cellHeight, fillPaint)
            canvas.drawText(status, 8f, gridTop + 58f, hiddenPaint)
        }
        return bitmap
    }

    private fun drawWeekEvents(
        canvas: Canvas,
        events: List<EventItem>,
        monthStart: LocalDate,
        month: YearMonth,
        gridTop: Float,
        cellWidth: Float,
        cellHeight: Float,
        eventPaint: Paint,
        hiddenPaint: Paint,
        fillPaint: Paint,
    ) {
        repeat(6) { row ->
            val weekStart = monthStart.plusDays((row * 7).toLong())
            val slotByEventId = widgetMultiDaySlotsForWeek(events, weekStart)
            val weekEvents = (0..6).map { day -> eventsForDate(events, weekStart.plusDays(day.toLong())) }
            val eventTop = gridTop + row * cellHeight + 65f
            val eventHeight = 28f
            val eventGap = 4f
            val capacity = ((cellHeight - 72f) / (eventHeight + eventGap)).toInt().coerceAtLeast(1)
            val reservedCapacityByDay = IntArray(7) { day ->
                if (weekEvents[day].size > capacity) (capacity - 1).coerceAtLeast(1) else capacity
            }
            val visibleByDay = IntArray(7)

            slotByEventId.entries.sortedBy { it.value }.forEach { (eventId, slot) ->
                val event = events.firstOrNull { it.id == eventId } ?: return@forEach
                val eventStart = event.startsAt.toLocalDate()
                val eventEnd = event.endsAt?.toLocalDate() ?: eventStart
                val segmentStart = maxOf(eventStart, weekStart)
                val segmentEnd = minOf(eventEnd, weekStart.plusDays(6))
                val startCol = segmentStart.dayOfWeek.value % 7
                val endCol = segmentEnd.dayOfWeek.value % 7
                var rangeStart: Int? = null
                for (day in startCol..endCol) {
                    val visible = slot < reservedCapacityByDay[day]
                    if (visible && rangeStart == null) {
                        rangeStart = day
                    } else if (!visible && rangeStart != null) {
                        drawMultiDaySegment(canvas, event, eventStart, weekStart, rangeStart, day - 1, slot, eventTop, eventHeight, eventGap, cellWidth, eventPaint, fillPaint)
                        rangeStart = null
                    }
                    if (visible) visibleByDay[day] += 1
                }
                if (rangeStart != null) {
                    drawMultiDaySegment(canvas, event, eventStart, weekStart, rangeStart, endCol, slot, eventTop, eventHeight, eventGap, cellWidth, eventPaint, fillPaint)
                }
            }

            repeat(7) { day ->
                val date = weekStart.plusDays(day.toLong())
                val dayEvents = weekEvents[day]
                val singleDayEvents = dayEvents.filterNot { it.isMultiDay() }.toMutableList()
                val reservedCapacity = reservedCapacityByDay[day]
                val slots = MutableList<EventItem?>(reservedCapacity) { null }
                dayEvents.filter { it.isMultiDay() }.forEach { event ->
                    val slot = slotByEventId[event.id]
                    if (slot != null && slot in 0 until reservedCapacity) slots[slot] = event
                }
                slots.indices.forEach { index ->
                    if (slots[index] == null && singleDayEvents.isNotEmpty()) {
                        val event = singleDayEvents.removeAt(0)
                        val left = day * cellWidth + 4f
                        val top = eventTop + index * (eventHeight + eventGap)
                        val right = (day + 1) * cellWidth - 4f
                        drawEventPill(canvas, left, top, right, top + eventHeight, softColor(calendarColor(event.calendarId)), fillPaint)
                        canvas.drawText(ellipsize(event.title, eventPaint, right - left - 8f), left + 5f, top + 21f, eventPaint)
                        visibleByDay[day] += 1
                    }
                }
                val hiddenCount = (dayEvents.size - visibleByDay[day]).coerceAtLeast(0)
                if (hiddenCount > 0) {
                    val top = eventTop + reservedCapacity * (eventHeight + eventGap)
                    canvas.drawText("+$hiddenCount", day * cellWidth + 8f, top + 21f, hiddenPaint)
                }
                if (date.month != month.month) {
                    eventPaint.color = Color.rgb(148, 163, 184)
                } else {
                    eventPaint.color = Color.rgb(15, 23, 42)
                }
            }
        }
    }

    private fun drawEventPill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, paint: Paint) {
        paint.color = color
        canvas.drawRoundRect(RectF(left, top, right, bottom), 3f, 3f, paint)
    }

    private fun drawMultiDaySegment(
        canvas: Canvas,
        event: EventItem,
        eventStart: LocalDate,
        weekStart: LocalDate,
        startCol: Int,
        endCol: Int,
        slot: Int,
        eventTop: Float,
        eventHeight: Float,
        eventGap: Float,
        cellWidth: Float,
        eventPaint: Paint,
        fillPaint: Paint,
    ) {
        val top = eventTop + slot * (eventHeight + eventGap)
        val left = startCol * cellWidth + 4f
        val right = (endCol + 1) * cellWidth - 4f
        drawEventPill(canvas, left, top, right, top + eventHeight, softColor(calendarColor(event.calendarId)), fillPaint)
        val segmentDate = weekStart.plusDays(startCol.toLong())
        val label = if (eventStart == segmentDate || segmentDate == weekStart) event.title else ""
        if (label.isNotBlank()) {
            canvas.drawText(ellipsize(label, eventPaint, right - left - 10f), left + 5f, top + 21f, eventPaint)
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

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var result = text
        while (result.isNotEmpty() && paint.measureText(result) > maxWidth) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
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
    }

    private data class WidgetMonthData(val events: List<EventItem>, val status: String?)
}
