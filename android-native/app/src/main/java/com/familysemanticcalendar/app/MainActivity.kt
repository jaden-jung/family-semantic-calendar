package com.familysemanticcalendar.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.hardware.biometrics.BiometricPrompt
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy년 M월")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private var user: User? = null
    private var visibleMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()
    private var calendars: List<CalendarItem> = emptyList()
    private var members: List<User> = emptyList()
    private var selectedCalendarId: String? = null
    private var events: List<EventItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = NativeStore.savedUser(this)
        if (saved == null) showLogin() else authenticateSavedUser(saved)
    }

    private fun authenticateSavedUser(saved: User) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            enterCalendar(saved)
            return
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("지문 인증")
            .setSubtitle("${saved.displayName} 사용자로 로그인")
            .setNegativeButton("다른 사용자", mainExecutor) { _, _ -> showLogin() }
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                enterCalendar(saved)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) showLogin()
            }

            override fun onAuthenticationFailed() {
                toast("지문 인증에 실패했습니다.")
            }
        })
    }

    private fun enterCalendar(foundUser: User) {
        user = foundUser
        showCalendar(loading = true)
        reloadCalendar()
    }

    private fun showLogin() {
        val root = LinearLayout(this).vertical().withPadding(28)
        root.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this).text("Family Calendar Native").size(26).bold()
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
        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prev = Button(this).apply { text = "<" }
        val next = Button(this).apply { text = ">" }
        val add = Button(this).apply { text = "+ 일정" }
        val search = Button(this).apply { text = "검색" }
        val settings = Button(this).apply { text = "설정" }
        val monthTitle = TextView(this).text(visibleMonth.format(monthFormatter)).size(20).bold().center()
        val calendarTitle = TextView(this).text(activeCalendarLabel()).size(14).muted()
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
        add.setOnClickListener { showEventDialog() }
        search.setOnClickListener { showSearchDialog() }
        settings.setOnClickListener { showCalendarDialog() }

        top.addView(prev, LinearLayout.LayoutParams(64, LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(monthTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(next, LinearLayout.LayoutParams(64, LinearLayout.LayoutParams.WRAP_CONTENT))
        secondRow.addView(add, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        secondRow.addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        secondRow.addView(settings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, matchWrap())
        root.addView(calendarTitle, matchWrap(top = 4))
        root.addView(secondRow, matchWrap(top = 8))
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
                if (selectedCalendarId == null || calendars.none { it.id == selectedCalendarId }) {
                    selectedCalendarId = calendars.firstOrNull()?.id
                }
                members = CalendarApi.listUsers(currentUser.id)
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
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { index, label ->
            grid.addView(dayText(label, header = true, sunday = index == 0, saturday = index == 6), cellParams(48))
        }
        val first = visibleMonth.atDay(1)
        val start = first.minusDays(first.dayOfWeek.value % 7L)
        repeat(42) { index ->
            val date = start.plusDays(index.toLong())
            val dayEvents = eventsForDate(events, date)
            val holiday = holidayName(date)
            val label = buildString {
                append(date.dayOfMonth)
                if (holiday != null) append("\n").append(holiday)
                if (dayEvents.isNotEmpty()) append("\n").append(dayEvents.first().title.take(8))
                if (dayEvents.size > 1) append(" +").append(dayEvents.size - 1)
            }
            val cell = dayText(
                value = label,
                inMonth = date.month == visibleMonth.month,
                today = date == LocalDate.now(),
                selected = date == selectedDate,
                sunday = date.dayOfWeek.value == 7 || holiday != null,
                saturday = date.dayOfWeek.value == 6,
            )
            cell.setOnClickListener {
                selectedDate = date
                visibleMonth = YearMonth.from(date)
                showCalendar()
            }
            grid.addView(cell, cellParams(92))
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
            val endText = event.endsAt?.takeIf { it.toLocalDate() != event.startsAt.toLocalDate() }?.let { " ~ ${it.toLocalDate().format(dateFormatter)}" } ?: ""
            val time = "%02d:%02d".format(event.startsAt.hour, event.startsAt.minute)
            val owner = ownerName(members, event.createdBy)
            container.addView(Button(this).apply {
                text = "$time  [$owner] ${event.title}$endText"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { showEventDialog(event) }
            }, matchWrap(top = 4))
        }
    }

    private fun showEventDialog(event: EventItem? = null) {
        val currentUser = user ?: return
        if (calendars.isEmpty()) {
            toast("먼저 달력을 만들어 주세요.")
            return
        }
        var date = event?.startsAt?.toLocalDate() ?: selectedDate
        var time = event?.startsAt?.toLocalTime() ?: LocalTime.of(9, 0)
        var endDate = event?.endsAt?.toLocalDate() ?: date

        val root = LinearLayout(this).vertical().withPadding(18)
        val calendarSpinner = Spinner(this)
        val calendarNames = calendars.map { it.name }
        calendarSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, calendarNames)
        val calendarIndex = calendars.indexOfFirst { it.id == (event?.calendarId ?: selectedCalendarId) }.coerceAtLeast(0)
        calendarSpinner.setSelection(calendarIndex)
        val ownerSpinner = Spinner(this)
        val owners = listOf(User(ALL_OWNER_ID, "모두")) + members
        ownerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, owners.map { it.displayName })
        val defaultOwnerId = event?.createdBy ?: currentUser.id
        val ownerIndex = owners.indexOfFirst { it.id == defaultOwnerId }.let { if (it >= 0) it else 0 }
        ownerSpinner.setSelection(ownerIndex)

        val dateButton = Button(this).apply { text = "날짜 ${date.format(dateFormatter)}" }
        val timeButton = Button(this).apply { text = "시간 %02d:%02d".format(time.hour, time.minute) }
        val periodCheck = CheckBox(this).apply {
            text = "기간 일정"
            isChecked = event?.endsAt?.toLocalDate()?.isAfter(date) == true
        }
        val repeatCheck = CheckBox(this).apply {
            text = "반복"
            isChecked = event?.recurrenceRule != null
        }
        val repeatSpinner = Spinner(this)
        val repeatLabels = listOf("매일", "매주", "매월", "매년")
        repeatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, repeatLabels)
        repeatSpinner.visibility = if (repeatCheck.isChecked) View.VISIBLE else View.GONE
        val repeatIndex = when (event?.recurrenceRule?.optString("frequency")) {
            "daily" -> 0
            "weekly" -> 1
            "monthly" -> 2
            "yearly" -> 3
            else -> 1
        }
        repeatSpinner.setSelection(repeatIndex)
        val endDateButton = Button(this).apply {
            text = "종료일 ${endDate.format(dateFormatter)}"
            visibility = if (periodCheck.isChecked) View.VISIBLE else View.GONE
        }
        val titleInput = EditText(this).apply {
            hint = "제목"
            setSingleLine(true)
            setText(event?.title.orEmpty())
        }
        val bodyInput = EditText(this).apply {
            hint = "설명"
            minLines = 3
            setText(event?.body.orEmpty())
        }
        val locationInput = EditText(this).apply {
            hint = "장소"
            setSingleLine(true)
            setText(event?.location.orEmpty())
        }

        fun refreshButtons() {
            dateButton.text = "날짜 ${date.format(dateFormatter)}"
            timeButton.text = "시간 %02d:%02d".format(time.hour, time.minute)
            endDateButton.text = "종료일 ${endDate.format(dateFormatter)}"
            endDateButton.visibility = if (periodCheck.isChecked) View.VISIBLE else View.GONE
            repeatSpinner.visibility = if (repeatCheck.isChecked) View.VISIBLE else View.GONE
        }

        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day)
                if (endDate.isBefore(date)) endDate = date
                refreshButtons()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        endDateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                endDate = LocalDate.of(year, month + 1, day)
                if (endDate.isBefore(date)) endDate = date
                refreshButtons()
            }, endDate.year, endDate.monthValue - 1, endDate.dayOfMonth).show()
        }
        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                time = LocalTime.of(hour, minute)
                refreshButtons()
            }, time.hour, time.minute, true).show()
        }
        periodCheck.setOnCheckedChangeListener { _, _ -> refreshButtons() }
        repeatCheck.setOnCheckedChangeListener { _, _ -> refreshButtons() }

        root.addView(TextView(this).text("달력").bold(), matchWrap())
        root.addView(calendarSpinner, matchWrap())
        root.addView(TextView(this).text("누구 일정").bold(), matchWrap(top = 8))
        root.addView(ownerSpinner, matchWrap())
        root.addView(dateButton, matchWrap(top = 8))
        root.addView(timeButton, matchWrap())
        root.addView(periodCheck, matchWrap())
        root.addView(endDateButton, matchWrap())
        root.addView(repeatCheck, matchWrap())
        root.addView(repeatSpinner, matchWrap())
        root.addView(titleInput, matchWrap(top = 8))
        root.addView(bodyInput, matchWrap())
        root.addView(locationInput, matchWrap())

        val builder = AlertDialog.Builder(this)
            .setTitle(if (event == null) "일정 추가" else "일정 수정")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
        if (event != null) builder.setNeutralButton("삭제", null)
        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) {
                    toast("제목을 입력해 주세요.")
                    return@setOnClickListener
                }
                val calendar = calendars[calendarSpinner.selectedItemPosition]
                val owner = owners[ownerSpinner.selectedItemPosition].id.let { if (it == ALL_OWNER_ID) null else it }
                val startsAt = LocalDateTime.of(date, time)
                val endsAt = if (periodCheck.isChecked) LocalDateTime.of(endDate, LocalTime.of(23, 59)) else null
                val recurrenceRule = if (repeatCheck.isChecked) buildRecurrenceRule(repeatSpinner.selectedItemPosition, date) else null
                background(
                    work = {
                        if (event == null) {
                            CalendarApi.createEvent(calendar.id, currentUser.id, title, bodyInput.text.toString(), locationInput.text.toString(), startsAt, endsAt, owner, recurrenceRule)
                        } else {
                            CalendarApi.updateEvent(event.id, currentUser.id, title, bodyInput.text.toString(), locationInput.text.toString(), startsAt, endsAt, owner, recurrenceRule)
                        }
                    },
                    done = {
                        dialog.dismiss()
                        selectedDate = date
                        visibleMonth = YearMonth.from(date)
                        reloadCalendar()
                    },
                )
            }
            if (event != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("일정 삭제")
                        .setMessage("이 일정을 삭제할까요?")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("삭제") { _, _ ->
                            background(
                                work = { CalendarApi.deleteEvent(event.id, currentUser.id) },
                                done = {
                                    dialog.dismiss()
                                    reloadCalendar()
                                },
                            )
                        }
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun buildRecurrenceRule(index: Int, date: LocalDate): JSONObject {
        val weekday = if (date.dayOfWeek.value == 7) 0 else date.dayOfWeek.value
        return when (index) {
            0 -> JSONObject().put("frequency", "daily").put("interval", 1)
            1 -> JSONObject().put("frequency", "weekly").put("interval", 1).put("weekdays", JSONArray(listOf(weekday)))
            2 -> JSONObject().put("frequency", "monthly").put("interval", 1).put("monthDay", date.dayOfMonth)
            else -> JSONObject().put("frequency", "yearly").put("interval", 1)
        }
    }

    private fun showSearchDialog() {
        val currentUser = user ?: return
        if (calendars.isEmpty()) {
            toast("검색할 달력이 없습니다.")
            return
        }
        val input = EditText(this).apply {
            hint = "검색어"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("일정 검색")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("검색") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) {
                    background(
                        work = { CalendarApi.searchEvents(calendars.map { it.id }, query, currentUser.id, NativeStore.searchMaxDistance(this)) },
                        done = { showSearchResults(it) },
                    )
                }
            }
            .show()
    }

    private fun showSearchResults(results: List<EventItem>) {
        if (results.isEmpty()) {
            toast("검색 결과가 없습니다.")
            return
        }
        val labels = results.map { event ->
            val score = event.similarity?.let { " · 유사도 ${"%.2f".format(it)}" } ?: ""
            "${event.startsAt.toLocalDate().format(dateFormatter)}  ${event.title}$score"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("검색 결과")
            .setItems(labels) { _, index ->
                val event = results[index]
                selectedDate = event.startsAt.toLocalDate()
                visibleMonth = YearMonth.from(selectedDate)
                showCalendar()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showCalendarDialog() {
        val currentUser = user ?: return
        val names = calendars.map { "선택: ${it.name}" }.toMutableList()
        if (selectedCalendarId != null) names.add("초대코드 복사")
        names.add("검색 임계치 설정 (${NativeStore.searchMaxDistance(this)})")
        names.add("초대코드로 참여")
        names.add("+ 새 달력 만들기")
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(names.toTypedArray()) { _, index ->
                if (index < calendars.size) {
                    selectedCalendarId = calendars[index].id
                    showCalendar()
                } else if (selectedCalendarId != null && index == calendars.size) {
                    copyInviteCode()
                } else if (index == calendars.size + if (selectedCalendarId != null) 1 else 0) {
                    showSearchThresholdDialog()
                } else if (index == calendars.size + if (selectedCalendarId != null) 2 else 1) {
                    showJoinCalendarDialog(currentUser)
                } else {
                    showCreateCalendarDialog(currentUser)
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun copyInviteCode() {
        val selected = calendars.find { it.id == selectedCalendarId }
        if (selected == null) {
            toast("선택된 달력이 없습니다.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("calendar invite code", selected.inviteCode))
        toast("초대코드를 복사했습니다.")
    }

    private fun showSearchThresholdDialog() {
        val input = EditText(this).apply {
            hint = "0.2"
            setSingleLine(true)
            setText(NativeStore.searchMaxDistance(this@MainActivity).toString())
        }
        AlertDialog.Builder(this)
            .setTitle("검색 임계치")
            .setMessage("낮을수록 더 비슷한 일정만 검색됩니다.")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val value = input.text.toString().toDoubleOrNull()
                if (value == null) {
                    toast("숫자로 입력해 주세요.")
                } else {
                    NativeStore.saveSearchMaxDistance(this, value)
                    toast("검색 임계치를 저장했습니다.")
                }
            }
            .show()
    }

    private fun showCreateCalendarDialog(currentUser: User) {
        val input = EditText(this).apply {
            hint = "달력 이름"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("새 달력 만들기")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("만들기") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    background(
                        work = { CalendarApi.createCalendar(name, currentUser.id) },
                        done = {
                            selectedCalendarId = it.id
                            reloadCalendar()
                        },
                    )
                }
            }
            .show()
    }

    private fun showJoinCalendarDialog(currentUser: User) {
        val input = EditText(this).apply {
            hint = "초대 코드"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("달력 참여")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("참여") { _, _ ->
                val inviteCode = input.text.toString().trim()
                if (inviteCode.isNotBlank()) {
                    background(
                        work = { CalendarApi.joinCalendar(inviteCode, currentUser.id) },
                        done = {
                            selectedCalendarId = it.id
                            reloadCalendar()
                        },
                    )
                }
            }
            .show()
    }

    private fun activeCalendarLabel(): String {
        if (calendars.isEmpty()) return "참여 중인 달력이 없습니다."
        val selected = calendars.find { it.id == selectedCalendarId }
        return selected?.name ?: "${calendars.size}개 달력"
    }

    private fun dayText(
        value: String,
        header: Boolean = false,
        inMonth: Boolean = true,
        today: Boolean = false,
        selected: Boolean = false,
        sunday: Boolean = false,
        saturday: Boolean = false,
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = if (header) 13f else 12f
            gravity = Gravity.CENTER
            setTextColor(
                when {
                    sunday -> 0xFFDC2626.toInt()
                    saturday -> 0xFF2563EB.toInt()
                    inMonth -> 0xFF0F172A.toInt()
                    else -> 0xFF94A3B8.toInt()
                }
            )
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
private fun cellParams(height: Int) = GridLayout.LayoutParams().apply {
    width = 0
    this.height = height
    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
    setMargins(1, 1, 1, 1)
}
