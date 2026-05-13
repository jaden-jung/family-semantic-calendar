package com.familysemanticcalendar.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy년 M월")
    private var user: User? = null
    private var visibleMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()
    private var calendars: List<CalendarItem> = emptyList()
    private var events: List<EventItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        user = NativeStore.savedUser(this)
        if (user == null) showLogin() else showCalendar(loading = true).also { reloadCalendar() }
    }

    private fun showLogin() {
        val root = LinearLayout(this).vertical().withPadding(28)
        root.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this).text("Family Calendar").size(26).bold()
        val nameInput = EditText(this).apply {
            hint = "사용자 이름"
            setSingleLine(true)
        }
        val usersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val signInButton = Button(this).apply { text = "기존 사용자 로그인" }
        val progress = ProgressBar(this).apply { visibility = View.GONE }

        root.addView(title, matchWrap())
        root.addView(nameInput, matchWrap(top = 18))
        root.addView(usersRow, matchWrap(top = 10))
        root.addView(signInButton, matchWrap(top = 12))
        root.addView(progress, wrapCenter(top = 12))
        setContentView(root)

        fun setLoading(loading: Boolean) {
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            signInButton.isEnabled = !loading
        }

        signInButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                toast("사용자 이름을 입력해 주세요.")
                return@setOnClickListener
            }
            setLoading(true)
            background(
                work = { CalendarApi.signIn(name) },
                done = {
                    user = it
                    NativeStore.saveUser(this, it)
                    refreshWidgets()
                    showCalendar(loading = true)
                    reloadCalendar()
                },
                failed = { setLoading(false) },
            )
        }

        setLoading(true)
        background(
            work = { CalendarApi.listSignInUsers() },
            done = { users ->
                setLoading(false)
                usersRow.removeAllViews()
                users.forEach { found ->
                    usersRow.addView(Button(this).apply {
                        text = found.displayName
                        setOnClickListener { nameInput.setText(found.displayName) }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = 8
                    })
                }
            },
            failed = { setLoading(false) },
        )
    }

    private fun showCalendar(loading: Boolean = false) {
        val root = LinearLayout(this).vertical().withPadding(10)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prev = Button(this).apply { text = "<" }
        val next = Button(this).apply { text = ">" }
        val monthTitle = TextView(this).text(visibleMonth.format(monthFormatter)).size(20).bold().center()
        val calendarGrid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
        }
        val listTitle = TextView(this).text("${selectedDate.monthValue}/${selectedDate.dayOfMonth} 일정").size(16).bold()
        val eventList = LinearLayout(this).vertical()
        val progress = ProgressBar(this).apply { visibility = if (loading) View.VISIBLE else View.GONE }

        prev.setOnClickListener {
            visibleMonth = visibleMonth.minusMonths(1)
            showCalendar()
        }
        next.setOnClickListener {
            visibleMonth = visibleMonth.plusMonths(1)
            showCalendar()
        }

        top.addView(prev, LinearLayout.LayoutParams(70, LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(monthTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(next, LinearLayout.LayoutParams(70, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(top, matchWrap())
        root.addView(calendarGrid, matchWrap(top = 8))
        root.addView(listTitle, matchWrap(top = 12))
        root.addView(eventList, matchWrap(top = 6))
        root.addView(progress, wrapCenter(top = 8))

        drawCalendar(calendarGrid)
        drawEventList(eventList, listTitle)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun reloadCalendar() {
        val currentUser = user ?: return
        background(
            work = {
                calendars = CalendarApi.listCalendars(currentUser.id)
                events = calendars.flatMap { CalendarApi.listEvents(it.id, currentUser.id) }
            },
            done = {
                refreshWidgets()
                showCalendar()
            },
        )
    }

    private fun drawCalendar(grid: GridLayout) {
        grid.removeAllViews()
        listOf("일", "월", "화", "수", "목", "금", "토").forEach { label ->
            grid.addView(dayText(label, header = true), cellParams())
        }
        val first = visibleMonth.atDay(1)
        val start = first.minusDays(first.dayOfWeek.value % 7L)
        repeat(42) { index ->
            val date = start.plusDays(index.toLong())
            val dayEvents = eventsForDate(events, date)
            val label = buildString {
                append(date.dayOfMonth)
                if (dayEvents.isNotEmpty()) append("\n").append(dayEvents.first().title.take(7))
            }
            val cell = dayText(label, inMonth = date.month == visibleMonth.month, today = date == LocalDate.now(), selected = date == selectedDate)
            cell.setOnClickListener {
                selectedDate = date
                showCalendar()
            }
            grid.addView(cell, cellParams())
        }
    }

    private fun drawEventList(container: LinearLayout, title: TextView) {
        container.removeAllViews()
        title.text = "${selectedDate.monthValue}/${selectedDate.dayOfMonth} 일정"
        val items = eventsForDate(events, selectedDate)
        if (items.isEmpty()) {
            container.addView(TextView(this).text("등록된 일정이 없습니다.").muted(), matchWrap())
            return
        }
        items.forEach { event ->
            val time = "%02d:%02d".format(event.startsAt.hour, event.startsAt.minute)
            container.addView(TextView(this).text("$time  ${event.title}").size(15).withPadding(8), matchWrap(top = 4))
        }
    }

    private fun dayText(value: String, header: Boolean = false, inMonth: Boolean = true, today: Boolean = false, selected: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = if (header) 13f else 12f
            gravity = Gravity.CENTER
            setTextColor(if (inMonth) 0xFF0F172A.toInt() else 0xFF94A3B8.toInt())
            if (header) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(
                when {
                    selected -> 0xFFD1FAE5.toInt()
                    today -> 0xFFFEF3C7.toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
            )
        }
    }

    private fun refreshWidgets() {
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, CalendarWidgetProvider::class.java)
        CalendarWidgetProvider().onUpdate(this, manager, manager.getAppWidgetIds(component))
    }

    private fun <T> background(work: () -> T, done: (T) -> Unit = {}, failed: () -> Unit = {}) {
        Thread {
            try {
                val result = work()
                runOnUiThread { done(result) }
            } catch (error: Exception) {
                runOnUiThread {
                    toast(error.message ?: "요청 실패")
                    failed()
                }
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private fun LinearLayout.vertical(): LinearLayout = apply { orientation = LinearLayout.VERTICAL }
private fun <T : View> T.withPadding(size: Int): T = apply { setPadding(size, size, size, size) }
private fun TextView.text(value: String): TextView = apply { text = value }
private fun TextView.size(value: Int): TextView = apply { textSize = value.toFloat() }
private fun TextView.bold(): TextView = apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
private fun TextView.center(): TextView = apply { gravity = Gravity.CENTER }
private fun TextView.muted(): TextView = apply { setTextColor(0xFF64748B.toInt()) }
private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
private fun wrapCenter(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
    topMargin = top
    gravity = Gravity.CENTER_HORIZONTAL
}
private fun cellParams() = ViewGroupMarginParams.grid()

private object ViewGroupMarginParams {
    fun grid(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = 0
            height = 96
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(1, 1, 1, 1)
        }
    }
}
