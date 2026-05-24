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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
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
import java.time.temporal.ChronoUnit

class MainActivity : Activity() {
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy년 M월")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private var user: User? = null
    private var visibleMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()
    private var dateSelected = false
    private var calendars: List<CalendarItem> = emptyList()
    private var members: List<User> = emptyList()
    private var selectedCalendarId: String? = null
    private var visibleCalendarIds: Set<String> = emptySet()
    private var events: List<EventItem> = emptyList()
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var listExpanded = false
    private var listHidden = false
    private var monthTransitionDirection = 0
    private var gestureAxis = 0
    private var activeSwipeViews: List<View> = emptyList()
    private var currentMonthView: View? = null
    private var previousMonthView: View? = null
    private var nextMonthView: View? = null
    private var currentCalendarGrid: GridLayout? = null
    private var currentEventList: LinearLayout? = null
    private var currentListTitle: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = NativeStore.savedUser(this)
        if (saved == null) showLogin() else authenticateSavedUser(saved)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (user != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    gestureAxis = 0
                    activeSwipeViews.forEach { it.animate().cancel() }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (gestureAxis == 0 && (kotlin.math.abs(dx) > 14.dp() || kotlin.math.abs(dy) > 14.dp())) {
                        gestureAxis = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) 1 else 2
                        if (gestureAxis == 2 && !canMoveVertical(dy)) {
                            gestureAxis = -1
                            resetGestureTransforms()
                            return true
                        }
                    }
                    if (gestureAxis == 1) {
                        moveMonthViews(dx)
                        return true
                    }
                    if (gestureAxis == 2) {
                        return true
                    }
                    if (gestureAxis == -1) return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    when {
                        kotlin.math.abs(dx) > 70.dp() && kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> {
                            animateMonthDragCommit(if (dx < 0) 1 else -1)
                            return true
                        }
                        kotlin.math.abs(dy) > 70.dp() && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> {
                            if (!applyVerticalListMode(dy)) return true
                            resetGestureTransforms()
                            monthTransitionDirection = 0
                            showCalendar()
                            playListResizeTransition()
                            return true
                        }
                        gestureAxis == 1 -> {
                            animateGestureReset()
                            return true
                        }
                        gestureAxis == 2 -> {
                            animateGestureReset()
                            return true
                        }
                        gestureAxis == -1 -> {
                            gestureAxis = 0
                            return true
                        }
                    }
                    gestureAxis = 0
                }
            }
        }
        return super.dispatchTouchEvent(event)
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
        val root = LinearLayout(this).vertical().withPadding(28.dp())
        root.gravity = Gravity.CENTER_VERTICAL
        root.setBackgroundColor(screenBg)

        val title = TextView(this).text("Family Calendar Native").size(26).bold().apply { setTextColor(slate900) }
        val nameInput = EditText(this).apply {
            hint = "사용자 이름"
            setSingleLine(true)
            background = rounded(Color.WHITE, 10.dp(), strokeColor = borderColor)
            setPadding(14.dp(), 0, 14.dp(), 0)
        }
        val usersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val signInButton = Button(this).apply { text = "기존 사용자 로그인" }.primaryButton()
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
                        compactButton()
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
        val frame = FrameLayout(this)
        frame.setBackgroundColor(screenBg)
        val root = LinearLayout(this).vertical().apply {
            setPadding(12.dp(), systemBarTopPadding() + 10.dp(), 12.dp(), systemBarBottomPadding() + 12.dp())
        }
        root.setBackgroundColor(screenBg)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = TextView(this).text("⌕").center().iconButton()
        val settings = TextView(this).text("⚙").center().iconButton()
        val today = TextView(this).text("오늘").center().todayChip()
        val addFab = TextView(this).text("+").center().apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(teal, 999.dp())
            elevation = 8.dp().toFloat()
            setOnClickListener { showEventDialog() }
        }
        val monthTitle = TextView(this).text(visibleMonth.format(monthFormatter)).size(22).bold().center().apply {
            setTextColor(slate900)
            setOnClickListener { showMonthPicker() }
        }
        val calendarTitle = TextView(this).text(activeCalendarLabel()).size(14).muted().apply {
            background = rounded(0xFFEFF6FF.toInt(), 999.dp())
            setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
        }
        val calendarGrid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
            background = rounded(Color.WHITE, 12.dp(), strokeColor = borderColor)
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        val previousGrid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
            background = rounded(Color.WHITE, 12.dp(), strokeColor = borderColor)
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        val nextGrid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
            background = rounded(Color.WHITE, 12.dp(), strokeColor = borderColor)
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        val calendarFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val listTitle = TextView(this).text(if (dateSelected) "${selectedDate.monthValue}/${selectedDate.dayOfMonth} 일정" else "날짜를 선택해 주세요").size(18).bold().apply { setTextColor(slate900) }
        val eventList = LinearLayout(this).vertical()
        val listPanel = LinearLayout(this).vertical().apply {
            background = rounded(Color.WHITE, 14.dp(), 0xFFE2E8F0.toInt())
            setPadding(14.dp(), 12.dp(), 14.dp(), 14.dp())
            visibility = if (listHidden) View.GONE else View.VISIBLE
        }
        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val progress = ProgressBar(this).apply { visibility = if (loading) View.VISIBLE else View.GONE }

        search.setOnClickListener { showSearchDialog() }
        settings.setOnClickListener { showCalendarDialog() }
        today.setOnClickListener {
            selectedDate = LocalDate.now()
            dateSelected = true
            visibleMonth = YearMonth.now()
            showCalendar()
        }

        top.addView(today, LinearLayout.LayoutParams(46.dp(), 34.dp()).apply {
            rightMargin = 8.dp()
        })
        top.addView(monthTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(search, LinearLayout.LayoutParams(40.dp(), 40.dp()).apply {
            leftMargin = 8.dp()
        })
        top.addView(settings, LinearLayout.LayoutParams(40.dp(), 40.dp()).apply {
            leftMargin = 6.dp()
        })
        root.addView(top, matchWrap())
        root.addView(calendarTitle, matchWrap(top = 4))
        calendarFrame.addView(previousGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        calendarFrame.addView(nextGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        calendarFrame.addView(calendarGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(calendarFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, calendarHeight()).apply {
            topMargin = 10.dp()
        })
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listPanel.addView(listHeader, matchWrap())
        listPanel.addView(ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(eventList)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 8.dp()
        })
        root.addView(listPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 12.dp()
        })
        root.addView(progress, wrapCenter(top = 8))

        drawCalendar(previousGrid, visibleMonth.minusMonths(1))
        drawCalendar(nextGrid, visibleMonth.plusMonths(1))
        drawCalendar(calendarGrid, visibleMonth)
        drawEventList(eventList, listTitle)
        currentEventList = eventList
        currentListTitle = listTitle
        activeSwipeViews = listOf(calendarGrid, listPanel)
        currentCalendarGrid = calendarGrid
        currentMonthView = calendarGrid
        previousMonthView = previousGrid
        nextMonthView = nextGrid
        prepareAdjacentMonthViews()
        frame.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(addFab, FrameLayout.LayoutParams(58.dp(), 58.dp(), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 18.dp()
            bottomMargin = systemBarBottomPadding() + 18.dp()
        })
        setContentView(frame)
        playMonthTransition(calendarGrid, listPanel)
    }

    private fun calendarHeight(): Int {
        val header = 32
        val cell = calendarCellHeight()
        val rowMargins = 0
        val gridPadding = 8
        return (header + 6 * cell + rowMargins + gridPadding).dp()
    }

    private fun calendarCellHeight(): Int = when {
        listHidden -> 78
        listExpanded -> 36
        else -> 66
    }

    private fun playMonthTransition(vararg views: View) {
        val direction = monthTransitionDirection
        if (direction == 0) return
        monthTransitionDirection = 0
        views.forEach { view ->
            view.post {
                val distance = view.rootView.width.coerceAtLeast(resources.displayMetrics.widthPixels) * 0.22f * direction
                view.translationX = distance
                view.alpha = 0.72f
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(230L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun prepareAdjacentMonthViews() {
        val width = resources.displayMetrics.widthPixels.toFloat()
        previousMonthView?.translationX = -width
        nextMonthView?.translationX = width
        previousMonthView?.alpha = 1f
        nextMonthView?.alpha = 1f
    }

    private fun moveMonthViews(dx: Float) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        currentMonthView?.translationX = dx
        previousMonthView?.translationX = dx - width
        nextMonthView?.translationX = dx + width
    }

    private fun moveVerticalViews(dy: Float) {
        val progress = (kotlin.math.abs(dy) / 220.dp()).coerceAtMost(1f)
        val calendarScale = if (dy < 0) 1f - progress * 0.08f else 0.92f + progress * 0.08f
        currentMonthView?.pivotY = 0f
        currentMonthView?.scaleY = calendarScale
        activeSwipeViews.drop(1).forEach { view ->
            view.translationY = dy * 0.28f
            view.alpha = 1f - progress * 0.08f
        }
    }

    private fun canMoveVertical(dy: Float): Boolean {
        return (dy < 0 && !listExpanded) || (dy > 0 && !listHidden)
    }

    private fun applyVerticalListMode(dy: Float): Boolean {
        return if (dy < 0) {
            when {
                listHidden -> {
                    listHidden = false
                    listExpanded = false
                    true
                }
                !listExpanded -> {
                    listExpanded = true
                    true
                }
                else -> false
            }
        } else {
            when {
                listExpanded -> {
                    listExpanded = false
                    true
                }
                !listHidden -> {
                    listHidden = true
                    true
                }
                else -> false
            }
        }
    }

    private fun resetGestureTransforms() {
        (activeSwipeViews + listOfNotNull(currentMonthView, previousMonthView, nextMonthView)).distinct().forEach {
            it.translationX = 0f
            it.translationY = 0f
            it.scaleY = 1f
            it.alpha = 1f
        }
        prepareAdjacentMonthViews()
    }

    private fun animateGestureReset() {
        (activeSwipeViews + listOfNotNull(currentMonthView)).distinct().forEach { view ->
            view.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        previousMonthView?.animate()
            ?.translationX(-resources.displayMetrics.widthPixels.toFloat())
            ?.setDuration(160L)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
        nextMonthView?.animate()
            ?.translationX(resources.displayMetrics.widthPixels.toFloat())
            ?.setDuration(160L)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
    }

    private fun animateMonthDragCommit(direction: Int) {
        val current = currentMonthView
        val incoming = if (direction > 0) nextMonthView else previousMonthView
        if (current == null || incoming == null) {
            monthTransitionDirection = direction
            visibleMonth = if (direction > 0) visibleMonth.plusMonths(1) else visibleMonth.minusMonths(1)
            dateSelected = false
            showCalendar()
            return
        }
        dateSelected = false
        refreshCurrentEventList()
        val width = resources.displayMetrics.widthPixels.toFloat()
        current.animate()
            .translationX(if (direction > 0) -width else width)
            .alpha(0.92f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                monthTransitionDirection = 0
                visibleMonth = if (direction > 0) visibleMonth.plusMonths(1) else visibleMonth.minusMonths(1)
                showCalendar()
            }
            .start()
    }

    private fun playListResizeTransition() {
        activeSwipeViews.forEach { view ->
            view.post {
                view.translationY = if (listExpanded) 24.dp().toFloat() else (-18).dp().toFloat()
                view.alpha = 0.78f
                view.animate()
                    .translationY(0f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(210L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun showMonthPicker() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp(), 10.dp(), 16.dp(), 4.dp())
        }
        val yearPicker = NumberPicker(this).apply {
            minValue = 1970
            maxValue = 2100
            value = visibleMonth.year
            wrapSelectorWheel = false
        }
        val monthPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            value = visibleMonth.monthValue
        }
        root.addView(yearPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(monthPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        AlertDialog.Builder(this)
            .setTitle("년월 선택")
            .setView(root)
            .setNegativeButton("취소", null)
            .setPositiveButton("이동") { _, _ ->
                visibleMonth = YearMonth.of(yearPicker.value, monthPicker.value)
                dateSelected = false
                showCalendar()
            }
            .show()
    }

    private fun reloadCalendar() {
        val currentUser = user ?: return
        background(
            work = {
                calendars = CalendarApi.listCalendars(currentUser.id)
                val calendarIds = calendars.map { it.id }.toSet()
                val savedVisible = NativeStore.visibleCalendarIds(this)
                visibleCalendarIds = if (visibleCalendarIds.isEmpty() && savedVisible.isNotEmpty()) {
                    savedVisible.intersect(calendarIds).ifEmpty { calendarIds }
                } else if (visibleCalendarIds.isEmpty()) {
                    calendarIds
                } else {
                    visibleCalendarIds.intersect(calendarIds).ifEmpty { calendarIds }
                }
                val savedDefault = NativeStore.defaultCalendarId(this)
                if (selectedCalendarId == null && savedDefault != null && calendars.any { it.id == savedDefault }) {
                    selectedCalendarId = savedDefault
                }
                if (selectedCalendarId == null || calendars.none { it.id == selectedCalendarId }) {
                    selectedCalendarId = visibleCalendars().firstOrNull()?.id ?: calendars.firstOrNull()?.id
                }
                NativeStore.saveVisibleCalendarIds(this, visibleCalendarIds)
                NativeStore.saveDefaultCalendarId(this, selectedCalendarId)
                members = CalendarApi.listUsers(currentUser.id)
                events = visibleCalendars().flatMap { CalendarApi.listEvents(it.id, currentUser.id) }
            },
            done = {
                refreshWidgets()
                showCalendar()
            },
        )
    }

    private fun drawCalendar(grid: GridLayout, month: YearMonth = visibleMonth) {
        grid.removeAllViews()
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { index, label ->
            grid.addView(dayText(label, header = true, sunday = index == 0, saturday = index == 6), cellParams(32))
        }
        val start = calendarGridStart(month)
        repeat(42) { index ->
            val date = start.plusDays(index.toLong())
            grid.addView(calendarCellFor(date, month), cellParams(calendarCellHeight()))
        }
    }

    private fun selectCalendarDate(date: LocalDate) {
        val previousDate = selectedDate.takeIf { dateSelected }
        selectedDate = date
        dateSelected = true
        val targetMonth = YearMonth.from(date)
        if (targetMonth == visibleMonth) {
            previousDate?.let { replaceCalendarCell(it) }
            replaceCalendarCell(date)
            refreshCurrentEventList()
        } else {
            visibleMonth = targetMonth
            showCalendar()
        }
    }

    private fun calendarGridStart(month: YearMonth): LocalDate {
        val first = month.atDay(1)
        return first.minusDays(first.dayOfWeek.value % 7L)
    }

    private fun calendarCellFor(date: LocalDate, month: YearMonth): LinearLayout {
        val holiday = holidayName(date)
        return dayCell(
            date = date,
            holiday = holiday,
            dayEvents = eventsForDate(events, date),
            inMonth = date.month == month.month,
            today = date == LocalDate.now(),
            selected = dateSelected && date == selectedDate,
            sunday = date.dayOfWeek.value == 7 || holiday != null,
            saturday = date.dayOfWeek.value == 6,
        ).apply {
            setOnClickListener { selectCalendarDate(date) }
        }
    }

    private fun replaceCalendarCell(date: LocalDate) {
        val grid = currentCalendarGrid ?: return
        val index = ChronoUnit.DAYS.between(calendarGridStart(visibleMonth), date).toInt()
        if (index !in 0 until 42) return
        val childIndex = index + 7
        if (childIndex >= grid.childCount) return
        grid.removeViewAt(childIndex)
        grid.addView(calendarCellFor(date, visibleMonth), childIndex, cellParams(calendarCellHeight()))
    }

    private fun refreshCurrentEventList() {
        val list = currentEventList ?: return
        val title = currentListTitle ?: return
        drawEventList(list, title)
    }

    private fun dayCell(
        date: LocalDate,
        holiday: String?,
        dayEvents: List<EventItem>,
        inMonth: Boolean,
        today: Boolean,
        selected: Boolean,
        sunday: Boolean,
        saturday: Boolean,
    ): LinearLayout {
        val cell = LinearLayout(this).vertical()
        cell.gravity = Gravity.START
        cell.setPadding(0, if (listExpanded) 3.dp() else 4.dp(), 0, 2.dp())
        cell.background = rounded(
            fillColor = when {
                selected -> 0xFFD1FAE5.toInt()
                today -> 0xFFFEF3C7.toInt()
                !inMonth -> 0xFFF1F5F9.toInt()
                else -> Color.WHITE
            },
            radius = 0,
            strokeColor = 0xFFE5E7EB.toInt(),
        )
        cell.foreground = if (selected) rounded(Color.TRANSPARENT, 0, teal, 2.dp()) else null

        val number = TextView(this).text(date.dayOfMonth.toString()).size(11).bold().apply {
            gravity = Gravity.START
            setPadding(3.dp(), 0, 3.dp(), 0)
            setTextColor(
                when {
                    sunday || holiday != null -> 0xFFDC2626.toInt()
                    saturday -> 0xFF2563EB.toInt()
                    inMonth -> slate900
                    else -> 0xFF94A3B8.toInt()
                }
            )
        }
        cell.addView(number, matchWrap())

        if (!listExpanded) {
            cell.addView(TextView(this).text(holiday?.take(4) ?: " ").size(9).center().apply {
                setTextColor(0xFFDC2626.toInt())
            }, matchWrap())
        }

        if (dayEvents.isNotEmpty()) {
            if (listExpanded) {
                val dots = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                dayEvents.map { calendarColor(it.calendarId) }.distinct().take(4).forEach { color ->
                    dots.addView(View(this).apply {
                        background = rounded(color, 999.dp())
                    }, LinearLayout.LayoutParams(6.dp(), 6.dp()).apply {
                        leftMargin = 1.dp()
                        rightMargin = 1.dp()
                    })
                }
                cell.addView(dots, matchWrap(top = 2))
            } else {
                dayEvents.take(3).forEach { event ->
                    val multiDay = event.isMultiDay()
                    val segmentStart = multiDay && (event.startsAt.toLocalDate() == date || date.dayOfWeek.value == 7)
                    val title = if (!multiDay || segmentStart) event.title.take(8) else " "
                    cell.addView(TextView(this).text(title).size(8).apply {
                        setTextColor(slate900)
                        maxLines = 1
                        gravity = Gravity.START
                        background = rounded(softCalendarColor(calendarColor(event.calendarId)), if (multiDay) 0 else 4.dp())
                        setPadding(3.dp(), 0, 3.dp(), 0)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 1.dp()
                        if (!multiDay || segmentStart) {
                            leftMargin = 3.dp()
                        }
                        if (!multiDay) {
                            rightMargin = 3.dp()
                        }
                    })
                }
            }
        }
        return cell
    }

    private fun drawEventList(container: LinearLayout, title: TextView) {
        container.removeAllViews()
        if (!dateSelected) {
            title.text = "날짜를 선택해 주세요"
            container.addView(TextView(this).text("날짜를 선택하면 일정이 여기에 표시됩니다.").muted(), matchWrap())
            return
        }
        title.text = "${selectedDate.monthValue}/${selectedDate.dayOfMonth} 일정"
        val items = eventsForDate(events, selectedDate)
        if (items.isEmpty()) {
            container.addView(TextView(this).text("등록된 일정이 없습니다.").muted(), matchWrap())
            return
        }
        items.forEach { event ->
            val time = "%02d:%02d".format(event.startsAt.hour, event.startsAt.minute).takeIf { event.startsAt.toLocalTime() != LocalTime.MIDNIGHT }
            val rangeText = eventRangeText(event)
            val owner = ownerName(members, event.createdBy)
            val meta = listOfNotNull(
                rangeText ?: time,
                event.location.takeIf { it.isNotBlank() },
                owner.takeIf { it.isNotBlank() }?.let { "[$it]" },
            ).joinToString("  ")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(Color.WHITE, 10.dp(), 0xFFE2E8F0.toInt())
                setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                setOnClickListener { showEventDialog(event) }
            }
            row.addView(View(this).apply {
                background = rounded(calendarColor(event.calendarId), 999.dp())
            }, LinearLayout.LayoutParams(5.dp(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                rightMargin = 10.dp()
            })
            val texts = LinearLayout(this).vertical()
            texts.addView(TextView(this).text(event.title).size(15).bold().apply {
                setTextColor(slate900)
                maxLines = 1
            }, matchWrap())
            texts.addView(TextView(this).text(meta).size(11).apply {
                setTextColor(slate600)
                maxLines = 1
            }, matchWrap(top = 2))
            if (event.body.isNotBlank()) {
                texts.addView(TextView(this).text(event.body).size(13).apply {
                    setTextColor(0xFF334155.toInt())
                    maxLines = 2
                }, matchWrap(top = 5))
            }
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row, matchWrap(top = 6))
        }
    }

    private fun eventRangeText(event: EventItem): String? {
        val end = event.endsAt ?: return null
        if (!event.isMultiDay()) return null
        val hasTime = event.startsAt.toLocalTime() != LocalTime.MIDNIGHT || end.toLocalTime() != LocalTime.of(23, 59)
        fun format(value: LocalDateTime): String {
            val dateText = value.toLocalDate().format(dateFormatter)
            return if (hasTime) "$dateText %02d:%02d".format(value.hour, value.minute) else dateText
        }
        return "${format(event.startsAt)} ~ ${format(end)}"
    }

    private fun showEventDialog(event: EventItem? = null) {
        val currentUser = user ?: return
        if (calendars.isEmpty()) {
            toast("먼저 달력을 만들어 주세요.")
            return
        }
        var date = event?.startsAt?.toLocalDate() ?: selectedDate.takeIf { dateSelected } ?: LocalDate.now()
        var time = event?.startsAt?.toLocalTime() ?: LocalTime.of(9, 0)
        var endDate = event?.endsAt?.toLocalDate() ?: date
        var endTime = event?.endsAt?.toLocalTime() ?: time

        val root = LinearLayout(this).vertical().apply {
            setPadding(18.dp(), 6.dp(), 18.dp(), 10.dp())
            setBackgroundColor(screenBg)
        }
        fun styleInput(input: EditText): EditText = input.apply {
            setTextColor(slate900)
            setHintTextColor(0xFF94A3B8.toInt())
            textSize = 15f
            background = rounded(0xFFFBFDFF.toInt(), 10.dp(), 0xFFCBD5E1.toInt())
            setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp())
            minHeight = 46.dp()
        }
        fun styleSpinner(spinner: Spinner): Spinner = spinner.apply {
            background = rounded(0xFFFBFDFF.toInt(), 10.dp(), 0xFFCBD5E1.toInt())
            setPadding(8.dp(), 0, 8.dp(), 0)
            minimumHeight = 46.dp()
        }
        fun stylePicker(button: Button): Button = button.apply {
            isAllCaps = false
            setTextColor(slate900)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(0xFFFBFDFF.toInt(), 10.dp(), 0xFFCBD5E1.toInt())
            minHeight = 46.dp()
        }
        fun panel(body: LinearLayout.() -> Unit): LinearLayout {
            return LinearLayout(this).vertical().apply {
                background = rounded(0xFFF8FAFC.toInt(), 14.dp(), 0xFFCBD5E1.toInt())
                setPadding(14.dp(), 12.dp(), 14.dp(), 14.dp())
                body()
            }
        }
        fun twoColumnRow(left: View, right: View, rightInvisible: Boolean = false): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = 5.dp()
                })
                if (rightInvisible) right.visibility = View.INVISIBLE
                addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = 5.dp()
                })
            }
        }
        val calendarSpinner = Spinner(this)
        val calendarNames = calendars.map { it.name }
        calendarSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, calendarNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        styleSpinner(calendarSpinner)
        val calendarIndex = calendars.indexOfFirst { it.id == (event?.calendarId ?: selectedCalendarId) }.coerceAtLeast(0)
        calendarSpinner.setSelection(calendarIndex)
        val ownerSpinner = Spinner(this)
        val owners = listOf(User(ALL_OWNER_ID, "모두")) + members
        ownerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, owners.map { it.displayName }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        styleSpinner(ownerSpinner)
        val defaultOwnerId = event?.createdBy ?: currentUser.id
        val ownerIndex = owners.indexOfFirst { it.id == defaultOwnerId }.let { if (it >= 0) it else 0 }
        ownerSpinner.setSelection(ownerIndex)

        val dateButton = stylePicker(Button(this).apply { text = date.format(dateFormatter) })
        val timeCheck = CheckBox(this).apply {
            text = "시간 선택"
            isChecked = event != null && event.startsAt.toLocalTime() != LocalTime.MIDNIGHT
            setTextColor(slate900)
        }
        val periodCheck = CheckBox(this).apply {
            text = "기간 일정"
            isChecked = event?.endsAt?.toLocalDate()?.isAfter(date) == true
            setTextColor(slate900)
        }
        val repeatCheck = CheckBox(this).apply {
            text = "반복"
            isChecked = event?.recurrenceRule != null
            setTextColor(slate900)
        }
        val repeatSpinner = Spinner(this)
        val repeatLabels = listOf("매일", "매주", "매월", "매년")
        repeatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, repeatLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        styleSpinner(repeatSpinner)
        repeatSpinner.visibility = if (repeatCheck.isChecked) View.VISIBLE else View.GONE
        val intervalSpinner = Spinner(this)
        val intervalLabels = (1..30).map { "$it" }
        intervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        styleSpinner(intervalSpinner)
        intervalSpinner.visibility = if (repeatCheck.isChecked) View.VISIBLE else View.GONE
        intervalSpinner.setSelection((event?.recurrenceRule?.optInt("interval", 1) ?: 1).coerceIn(1, 30) - 1)
        val weekdayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val weekdayChecks = listOf("일", "월", "화", "수", "목", "금", "토").mapIndexed { index, label ->
            CheckBox(this).apply {
                text = label
                textSize = 12f
                setTextColor(slate900)
                isChecked = event?.recurrenceRule?.optJSONArray("weekdays")?.let { days ->
                    (0 until days.length()).any { days.optInt(it) == index }
                } ?: (index == (if (date.dayOfWeek.value == 7) 0 else date.dayOfWeek.value))
                weekdayRow.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val monthlyModeSpinner = Spinner(this)
        val monthlyModeLabels = listOf("같은 일자", "몇번째 주 요일")
        monthlyModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, monthlyModeLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        styleSpinner(monthlyModeSpinner)
        monthlyModeSpinner.visibility = View.GONE
        monthlyModeSpinner.setSelection(if (event?.recurrenceRule?.optString("mode") == "nthWeekday") 1 else 0)
        val lunarCheck = CheckBox(this).apply {
            text = "음력 날짜로 매년 반복"
            setTextColor(slate900)
            isChecked = event?.recurrenceRule?.optBoolean("lunar", false) == true
            visibility = View.GONE
        }
        val repeatIndex = when (event?.recurrenceRule?.optString("frequency")) {
            "daily" -> 0
            "weekly" -> 1
            "monthly" -> 2
            "yearly" -> 3
            else -> 1
        }
        repeatSpinner.setSelection(repeatIndex)
        val endDateButton = stylePicker(Button(this).apply {
            text = endDate.format(dateFormatter)
        })
        val rangeDivider = TextView(this).text("~").center().bold().apply {
            setTextColor(slate600)
            textSize = 18f
        }
        val titleInput = styleInput(EditText(this).apply {
            hint = "제목"
            setSingleLine(true)
            setText(event?.title.orEmpty())
        })
        val bodyInput = styleInput(EditText(this).apply {
            hint = "설명"
            textSize = 14f
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setText(event?.body.orEmpty())
        })
        val locationInput = styleInput(EditText(this).apply {
            hint = "장소"
            setSingleLine(true)
            setText(event?.location.orEmpty())
        })

        fun refreshButtons() {
            dateButton.text = if (timeCheck.isChecked) {
                "${date.format(dateFormatter)} %02d:%02d".format(time.hour, time.minute)
            } else {
                date.format(dateFormatter)
            }
            endDateButton.text = if (timeCheck.isChecked) {
                "${endDate.format(dateFormatter)} %02d:%02d".format(endTime.hour, endTime.minute)
            } else {
                endDate.format(dateFormatter)
            }
            endDateButton.visibility = if (periodCheck.isChecked) View.VISIBLE else View.INVISIBLE
            rangeDivider.visibility = if (periodCheck.isChecked) View.VISIBLE else View.INVISIBLE
            val repeatVisible = repeatCheck.isChecked
            repeatSpinner.visibility = if (repeatVisible) View.VISIBLE else View.GONE
            intervalSpinner.visibility = if (repeatVisible && repeatSpinner.selectedItemPosition in 0..1) View.VISIBLE else View.GONE
            weekdayRow.visibility = if (
                repeatVisible &&
                (repeatSpinner.selectedItemPosition == 1 || (repeatSpinner.selectedItemPosition == 2 && monthlyModeSpinner.selectedItemPosition == 1))
            ) View.VISIBLE else View.GONE
            monthlyModeSpinner.visibility = if (repeatVisible && repeatSpinner.selectedItemPosition == 2) View.VISIBLE else View.GONE
            lunarCheck.visibility = if (repeatVisible && repeatSpinner.selectedItemPosition == 3) View.VISIBLE else View.GONE
        }

        fun pickDateTime(
            initialDate: LocalDate,
            initialTime: LocalTime,
            onPicked: (LocalDate, LocalTime) -> Unit,
        ) {
            DatePickerDialog(this, { _, year, month, day ->
                val pickedDate = LocalDate.of(year, month + 1, day)
                if (timeCheck.isChecked) {
                    TimePickerDialog(this, { _, hour, minute ->
                        onPicked(pickedDate, LocalTime.of(hour, minute))
                    }, initialTime.hour, initialTime.minute, true).show()
                } else {
                    onPicked(pickedDate, initialTime)
                }
            }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth).show()
        }
        dateButton.setOnClickListener {
            pickDateTime(date, time) { pickedDate, pickedTime ->
                date = pickedDate
                time = pickedTime
                if (endDate.isBefore(date)) endDate = date
                refreshButtons()
            }
        }
        endDateButton.setOnClickListener {
            pickDateTime(endDate, endTime) { pickedDate, pickedTime ->
                endDate = pickedDate
                endTime = pickedTime
                if (endDate.isBefore(date)) endDate = date
                refreshButtons()
            }
        }
        periodCheck.setOnCheckedChangeListener { _, _ -> refreshButtons() }
        timeCheck.setOnCheckedChangeListener { _, _ -> refreshButtons() }
        repeatCheck.setOnCheckedChangeListener { _, _ -> refreshButtons() }
        repeatSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshButtons()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        monthlyModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshButtons()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        refreshButtons()
        root.addView(panel {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(periodCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(timeCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWrap())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(dateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(rangeDivider, LinearLayout.LayoutParams(28.dp(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = 4.dp()
                    rightMargin = 4.dp()
                    gravity = Gravity.CENTER_VERTICAL
                })
                addView(endDateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWrap(top = 8))
        }, matchWrap(top = 12))
        root.addView(panel {
            addView(titleInput, matchWrap())
            addView(bodyInput, matchWrap(top = 8))
            addView(locationInput, matchWrap(top = 8))
        }, matchWrap(top = 12))
        root.addView(panel {
            addView(calendarSpinner, matchWrap())
            addView(ownerSpinner, matchWrap(top = 8))
        }, matchWrap(top = 12))
        root.addView(panel {
            addView(repeatCheck, matchWrap())
            addView(repeatSpinner, matchWrap(top = 6))
            addView(intervalSpinner, matchWrap(top = 6))
            addView(weekdayRow, matchWrap(top = 4))
            addView(monthlyModeSpinner, matchWrap(top = 4))
            addView(lunarCheck, matchWrap(top = 4))
        }, matchWrap(top = 12))

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
                val startsAt = LocalDateTime.of(date, if (timeCheck.isChecked) time else LocalTime.MIDNIGHT)
                val endsAt = if (periodCheck.isChecked) {
                    LocalDateTime.of(endDate, if (timeCheck.isChecked) endTime else LocalTime.of(23, 59))
                } else {
                    null
                }
                val recurrenceRule = if (repeatCheck.isChecked) {
                    buildRecurrenceRule(
                        repeatSpinner.selectedItemPosition,
                        date,
                        if (repeatSpinner.selectedItemPosition in 0..1) intervalSpinner.selectedItemPosition + 1 else 1,
                        weekdayChecks.mapIndexedNotNull { index, check -> if (check.isChecked) index else null },
                        monthlyModeSpinner.selectedItemPosition,
                        lunarCheck.isChecked,
                    )
                } else null
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
                        dateSelected = true
                        visibleMonth = YearMonth.from(date)
                        listExpanded = false
                        listHidden = false
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
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(teal)
            setTypeface(typeface, Typeface.BOLD)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(slate600)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFFDC2626.toInt())
    }

    private fun buildRecurrenceRule(
        index: Int,
        date: LocalDate,
        interval: Int,
        weekdays: List<Int>,
        monthlyMode: Int,
        lunar: Boolean,
    ): JSONObject {
        val weekday = if (date.dayOfWeek.value == 7) 0 else date.dayOfWeek.value
        return when (index) {
            0 -> JSONObject().put("frequency", "daily").put("interval", interval)
            1 -> JSONObject()
                .put("frequency", "weekly")
                .put("interval", interval.coerceIn(1, 3))
                .put("weekdays", JSONArray(weekdays.ifEmpty { listOf(weekday) }))
            2 -> JSONObject()
                .put("frequency", "monthly")
                .put("interval", interval)
                .put("mode", if (monthlyMode == 1) "nthWeekday" else "monthDay")
                .put("monthDay", date.dayOfMonth)
                .put("weekOfMonth", ((date.dayOfMonth - 1) / 7) + 1)
                .put("weekday", weekday)
            else -> JSONObject()
                .put("frequency", "yearly")
                .put("interval", interval)
                .put("lunar", lunar)
                .put("lunarMonth", (if (lunar) lunarMonthDay(date)?.first else null) ?: date.monthValue)
                .put("lunarDay", (if (lunar) lunarMonthDay(date)?.second else null) ?: date.dayOfMonth)
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
                        work = { CalendarApi.searchEvents(visibleCalendars().map { it.id }, query, currentUser.id, NativeStore.searchMaxDistance(this)) },
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
                dateSelected = true
                listExpanded = false
                listHidden = false
                showCalendar()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showCalendarDialog() {
        val currentUser = user ?: return
        val names = mutableListOf(
            "표시 달력 선택",
            "기본 등록 달력 선택",
        )
        if (selectedCalendarId != null) names.add("초대코드 복사")
        names.add("검색 임계치 설정 (${NativeStore.searchMaxDistance(this)})")
        names.add("초대코드로 참여")
        names.add("+ 새 달력 만들기")
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(names.toTypedArray()) { _, index ->
                when (names[index]) {
                    "표시 달력 선택" -> showVisibleCalendarsDialog()
                    "기본 등록 달력 선택" -> showDefaultCalendarDialog()
                    "초대코드 복사" -> copyInviteCode()
                    "초대코드로 참여" -> showJoinCalendarDialog(currentUser)
                    "+ 새 달력 만들기" -> showCreateCalendarDialog(currentUser)
                    else -> showSearchThresholdDialog()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showVisibleCalendarsDialog() {
        if (calendars.isEmpty()) {
            toast("참여 중인 달력이 없습니다.")
            return
        }
        val names = calendars.map { it.name }.toTypedArray()
        val checked = calendars.map { visibleCalendarIds.contains(it.id) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("표시 달력 선택")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton("취소", null)
            .setPositiveButton("적용") { _, _ ->
                val selected = calendars.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                if (selected.isEmpty()) {
                    toast("하나 이상 선택해 주세요.")
                } else {
                    visibleCalendarIds = selected
                    if (selectedCalendarId !in visibleCalendarIds) selectedCalendarId = visibleCalendars().firstOrNull()?.id
                    NativeStore.saveVisibleCalendarIds(this, visibleCalendarIds)
                    NativeStore.saveDefaultCalendarId(this, selectedCalendarId)
                    reloadCalendar()
                }
            }
            .show()
    }

    private fun showDefaultCalendarDialog() {
        if (calendars.isEmpty()) {
            toast("참여 중인 달력이 없습니다.")
            return
        }
        val names = calendars.map { it.name }.toTypedArray()
        val selectedIndex = calendars.indexOfFirst { it.id == selectedCalendarId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("기본 등록 달력")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                selectedCalendarId = calendars[which].id
                if (selectedCalendarId !in visibleCalendarIds) visibleCalendarIds = visibleCalendarIds + selectedCalendarId!!
                NativeStore.saveDefaultCalendarId(this, selectedCalendarId)
                NativeStore.saveVisibleCalendarIds(this, visibleCalendarIds)
                dialog.dismiss()
                showCalendar()
            }
            .setNegativeButton("취소", null)
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
                            visibleCalendarIds = visibleCalendarIds + it.id
                            NativeStore.saveDefaultCalendarId(this, it.id)
                            NativeStore.saveVisibleCalendarIds(this, visibleCalendarIds)
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
                            visibleCalendarIds = visibleCalendarIds + it.id
                            NativeStore.saveDefaultCalendarId(this, it.id)
                            NativeStore.saveVisibleCalendarIds(this, visibleCalendarIds)
                            reloadCalendar()
                        },
                    )
                }
            }
            .show()
    }

    private fun activeCalendarLabel(): String {
        if (calendars.isEmpty()) return "참여 중인 달력이 없습니다."
        val visible = visibleCalendars()
        if (visible.size > 1) return visible.joinToString(" · ") { it.name }
        val selected = calendars.find { it.id == selectedCalendarId }
        return selected?.name ?: "${calendars.size}개 달력"
    }

    private fun visibleCalendars(): List<CalendarItem> {
        return calendars.filter { visibleCalendarIds.contains(it.id) }.ifEmpty { calendars }
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
            gravity = if (header) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(3.dp(), if (header) 0 else 5.dp(), 3.dp(), 3.dp())
            setTextColor(
                when {
                    sunday -> 0xFFDC2626.toInt()
                    saturday -> 0xFF2563EB.toInt()
                    inMonth -> 0xFF0F172A.toInt()
                    else -> 0xFF94A3B8.toInt()
                }
            )
            if (header) setTypeface(typeface, Typeface.BOLD)
            background = rounded(
                fillColor = when {
                    selected -> 0xFFD1FAE5.toInt()
                    today -> 0xFFFEF3C7.toInt()
                    !inMonth -> 0xFFF1F5F9.toInt()
                    else -> Color.WHITE
                },
                radius = if (header) 0 else 8.dp(),
                strokeColor = if (selected) teal else 0xFFE2E8F0.toInt(),
                strokeWidth = if (selected) 2.dp() else 1
            )
        }
    }

    private fun refreshWidgets() {
        val manager = AppWidgetManager.getInstance(this)
        val agendaComponent = ComponentName(this, CalendarWidgetProvider::class.java)
        CalendarWidgetProvider().onUpdate(this, manager, manager.getAppWidgetIds(agendaComponent))
        val monthComponent = ComponentName(this, CalendarMonthWidgetProvider::class.java)
        CalendarMonthWidgetProvider().onUpdate(this, manager, manager.getAppWidgetIds(monthComponent))
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

    private fun systemBarTopPadding(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun systemBarBottomPadding(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}

private val teal = 0xFF0F766E.toInt()
private val tealSoft = 0xFFE6FFFA.toInt()
private val slate900 = 0xFF0F172A.toInt()
private val slate600 = 0xFF475569.toInt()
private val borderColor = 0xFFCBD5E1.toInt()
private val screenBg = 0xFFF7FAF9.toInt()
private val calendarPalette = listOf(
    0xFF0F766E.toInt(),
    0xFF2563EB.toInt(),
    0xFFC2410C.toInt(),
    0xFF7C3AED.toInt(),
    0xFFBE123C.toInt(),
    0xFF15803D.toInt(),
)

private fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
private fun LinearLayout.vertical(): LinearLayout = apply { orientation = LinearLayout.VERTICAL }
private fun <T : View> T.withPadding(size: Int): T = apply { setPadding(size, size, size, size) }
private fun TextView.text(value: String): TextView = apply { text = value }
private fun TextView.size(value: Int): TextView = apply { textSize = value.toFloat() }
private fun TextView.bold(): TextView = apply { setTypeface(typeface, Typeface.BOLD) }
private fun TextView.center(): TextView = apply { gravity = Gravity.CENTER }
private fun TextView.muted(): TextView = apply { setTextColor(0xFF64748B.toInt()) }
private fun TextView.iconButton(): TextView = apply {
    setTextColor(slate900)
    textSize = 22f
    setTypeface(typeface, Typeface.BOLD)
    background = rounded(Color.WHITE, 999.dp(), borderColor)
}
private fun TextView.todayChip(): TextView = apply {
    setTextColor(teal)
    textSize = 12f
    setTypeface(typeface, Typeface.BOLD)
    background = rounded(tealSoft, 999.dp(), 0xFF99F6E4.toInt())
}
private fun rounded(fillColor: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fillColor)
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }
}
private fun Button.primaryButton(): Button = apply {
    setTextColor(Color.WHITE)
    setTypeface(typeface, Typeface.BOLD)
    background = rounded(teal, 8.dp())
    minHeight = 42.dp()
}
private fun Button.secondaryButton(): Button = apply {
    setTextColor(slate900)
    setTypeface(typeface, Typeface.BOLD)
    background = rounded(0xFFE2E8F0.toInt(), 8.dp())
    minHeight = 42.dp()
}
private fun Button.navButton(): Button = apply {
    setTextColor(slate900)
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    background = rounded(Color.WHITE, 999.dp(), borderColor)
    minHeight = 40.dp()
}
private fun Button.compactButton(): Button = apply {
    setTextColor(teal)
    background = rounded(tealSoft, 999.dp(), 0xFF99F6E4.toInt())
    minHeight = 34.dp()
}
private fun Button.eventButton(): Button = apply {
    setTextColor(slate900)
    textSize = 14f
    background = rounded(Color.WHITE, 8.dp(), 0xFFE2E8F0.toInt())
    minHeight = 44.dp()
    setPadding(12.dp(), 0, 12.dp(), 0)
}
private fun calendarColor(calendarId: String): Int {
    val index = kotlin.math.abs(calendarId.hashCode()) % calendarPalette.size
    return calendarPalette[index]
}
private fun softCalendarColor(color: Int): Int {
    return Color.rgb(
        (Color.red(color) * 0.14f + 255 * 0.86f).toInt(),
        (Color.green(color) * 0.14f + 255 * 0.86f).toInt(),
        (Color.blue(color) * 0.14f + 255 * 0.86f).toInt(),
    )
}
private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
private fun wrapCenter(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
    topMargin = top
    gravity = Gravity.CENTER_HORIZONTAL
}
private fun cellParams(height: Int) = GridLayout.LayoutParams().apply {
    width = 0
    this.height = height.dp()
    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
    setMargins(0, 0, 0, 0)
}
