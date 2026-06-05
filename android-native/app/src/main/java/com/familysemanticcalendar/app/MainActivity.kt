package com.familysemanticcalendar.app

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val ICS_IMPORT_REQUEST = 4101

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
    private var previousCalendarGrid: GridLayout? = null
    private var nextCalendarGrid: GridLayout? = null
    private var currentMonthOverlay: MultiDayTitleOverlay? = null
    private var previousMonthOverlay: MultiDayTitleOverlay? = null
    private var nextMonthOverlay: MultiDayTitleOverlay? = null
    private var currentMonthTitle: TextView? = null
    private var currentEventList: LinearLayout? = null
    private var currentListTitle: TextView? = null
    private var currentListPanel: View? = null
    private var resizeAreaTop = -1
    private var resizeAreaBottom = -1
    private var resizeGestureAllowed = false
    private val calendarEventCache = mutableMapOf<LocalDate, List<EventItem?>>()
    private val multiDaySlotCache = mutableMapOf<LocalDate, Map<String, Int>>()
    private val visibleEventCache = linkedMapOf<String, List<EventItem>>()
    private var eventLoadVersion = 0

    private data class IcsImportEvent(
        val title: String,
        val body: String,
        val location: String,
        val startsAt: LocalDateTime,
        val endsAt: LocalDateTime?,
        val recurrenceRule: JSONObject?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = NativeStore.savedSession(this)
        if (saved == null) {
            showLogin()
        } else {
            CalendarApi.accessToken = saved.accessToken
            enterCalendar(saved.user)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (user != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    resizeGestureAllowed = event.rawY.toInt() in resizeAreaTop..resizeAreaBottom
                    gestureAxis = 0
                    activeSwipeViews.forEach { it.animate().cancel() }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (gestureAxis == 0 && (kotlin.math.abs(dx) > 14.dp() || kotlin.math.abs(dy) > 14.dp())) {
                        gestureAxis = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) 1 else 2
                        if (gestureAxis == 2 && (!resizeGestureAllowed || !canMoveVertical(dy))) {
                            gestureAxis = -1
                            if (resizeGestureAllowed) {
                                resetGestureTransforms()
                                return true
                            }
                        }
                    }
                    if (gestureAxis == 1) {
                        moveMonthViews(dx)
                        return true
                    }
                    if (gestureAxis == 2 && resizeGestureAllowed) {
                        if (kotlin.math.abs(dy) > 28.dp() && kotlin.math.abs(dy) > kotlin.math.abs(dx) && applyVerticalListMode(dy)) {
                            resetGestureTransforms()
                            monthTransitionDirection = 0
                            resizeGestureAllowed = false
                            gestureAxis = -1
                            showCalendar()
                        }
                        return true
                    }
                    if (gestureAxis == -1 && resizeGestureAllowed) return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    when {
                        kotlin.math.abs(dx) > 70.dp() && kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> {
                            animateMonthDragCommit(if (dx < 0) 1 else -1)
                            return true
                        }
                        resizeGestureAllowed && kotlin.math.abs(dy) > 28.dp() && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> {
                            if (!applyVerticalListMode(dy)) return true
                            resetGestureTransforms()
                            monthTransitionDirection = 0
                            showCalendar()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ICS_IMPORT_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { importIcsFromUri(it) }
        }
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
        clearVisibleEventCache()
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
        val passwordInput = EditText(this).apply {
            hint = "비밀번호"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
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
        root.addView(passwordInput, matchWrap(top = 10))
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
            val password = passwordInput.text.toString()
            if (name.isBlank()) {
                toast("사용자 이름을 입력해 주세요.")
                return@setOnClickListener
            }
            if (password.isBlank()) {
                toast("비밀번호를 입력해 주세요.")
                return@setOnClickListener
            }
            setLoading(true)
            background(
                work = { CalendarApi.signIn(name, password) },
                done = { session ->
                    user = session.user
                    NativeStore.saveSession(this, session)
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
            setOnClickListener { showVisibleCalendarsDialog() }
        }
        val calendarGrid = calendarGridView()
        val previousGrid = calendarGridView()
        val nextGrid = calendarGridView()
        val calendarOverlay = MultiDayTitleOverlay(this, visibleMonth, calendarGrid)
        val previousOverlay = MultiDayTitleOverlay(this, visibleMonth.minusMonths(1), previousGrid)
        val nextOverlay = MultiDayTitleOverlay(this, visibleMonth.plusMonths(1), nextGrid)
        val calendarPage = calendarPage(calendarGrid, calendarOverlay)
        val previousPage = calendarPage(previousGrid, previousOverlay)
        val nextPage = calendarPage(nextGrid, nextOverlay)
        val calendarFrame = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            post { updateResizeAreaTop(this) }
        }
        val listTitle = TextView(this).text(if (dateSelected) "${selectedDate.monthValue}/${selectedDate.dayOfMonth} 일정" else "날짜를 선택해 주세요").size(18).bold().apply { setTextColor(slate900) }
        val eventList = LinearLayout(this).vertical()
        val listPanel = LinearLayout(this).vertical().apply {
            background = rounded(Color.WHITE, 14.dp(), 0xFFE2E8F0.toInt())
            setPadding(14.dp(), 12.dp(), 14.dp(), 14.dp())
            visibility = if (listHidden) View.GONE else View.VISIBLE
        }
        val resizeHandle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 7.dp(), 0, 5.dp())
            addView(View(this@MainActivity).apply {
                background = rounded(0xFF94A3B8.toInt(), 999.dp())
            }, LinearLayout.LayoutParams(44.dp(), 4.dp()))
            post {
                updateResizeAreaBottom(this)
            }
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
            reloadVisibleEvents()
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
        calendarFrame.addView(previousPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        calendarFrame.addView(nextPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        calendarFrame.addView(calendarPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(
            calendarFrame,
            if (listHidden) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = 10.dp()
                    bottomMargin = 72.dp()
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, calendarHeight()).apply {
                    topMargin = 10.dp()
                }
            },
        )
        root.addView(resizeHandle, matchWrap(top = if (listHidden) 2 else 4))
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listPanel.addView(listHeader, matchWrap())
        listPanel.addView(ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(eventList)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 8.dp()
        })
        if (!listHidden) {
            root.addView(listPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = 12.dp()
            })
        }
        root.addView(progress, wrapCenter(top = 8))

        calendarEventCache.clear()
        multiDaySlotCache.clear()
        drawCalendar(previousGrid, visibleMonth.minusMonths(1))
        drawCalendar(nextGrid, visibleMonth.plusMonths(1))
        drawCalendar(calendarGrid, visibleMonth)
        drawEventList(eventList, listTitle)
        currentMonthTitle = monthTitle
        currentEventList = eventList
        currentListTitle = listTitle
        currentListPanel = listPanel
        activeSwipeViews = listOf(calendarPage, listPanel)
        currentCalendarGrid = calendarGrid
        previousCalendarGrid = previousGrid
        nextCalendarGrid = nextGrid
        currentMonthOverlay = calendarOverlay
        previousMonthOverlay = previousOverlay
        nextMonthOverlay = nextOverlay
        currentMonthView = calendarPage
        previousMonthView = previousPage
        nextMonthView = nextPage
        prepareAdjacentMonthViews()
        calendarFrame.post { prepareAdjacentMonthViews() }
        frame.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(addFab, FrameLayout.LayoutParams(58.dp(), 58.dp(), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 18.dp()
            bottomMargin = systemBarBottomPadding() + 18.dp()
        })
        setContentView(frame)
        playMonthTransition(calendarPage, listPanel)
    }

    private fun calendarHeight(): Int {
        val header = 32
        val cell = calendarCellHeight()
        val rowMargins = 0
        val gridPadding = 0
        return (header + 6 * cell + rowMargins + gridPadding).dp()
    }

    private fun calendarCellHeight(): Int {
        if (!listHidden) return if (listExpanded) 37 else 67
        val density = resources.displayMetrics.density
        val reservedPx = systemBarTopPadding() +
            systemBarBottomPadding() +
            10.dp() + 40.dp() + 4.dp() + 34.dp() + 10.dp() + 32.dp() + 8.dp() + 72.dp()
        val cellPx = ((resources.displayMetrics.heightPixels - reservedPx) / 6).coerceAtLeast(78.dp())
        return (cellPx / density).toInt()
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
        val width = monthDragWidth()
        previousMonthView?.translationX = -width
        nextMonthView?.translationX = width
        previousMonthView?.alpha = 1f
        nextMonthView?.alpha = 1f
    }

    private fun moveMonthViews(dx: Float) {
        val width = monthDragWidth()
        currentMonthView?.translationX = dx
        previousMonthView?.translationX = dx - width
        nextMonthView?.translationX = dx + width
    }

    private fun monthDragWidth(): Float {
        val parentWidth = (currentMonthView?.parent as? View)?.width ?: currentMonthView?.width ?: 0
        return parentWidth.takeIf { it > 0 }?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()
    }

    private fun updateResizeAreaTop(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        resizeAreaTop = location[1]
    }

    private fun updateResizeAreaBottom(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        resizeAreaBottom = location[1] + view.height + 12.dp()
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
            ?.translationX(-monthDragWidth())
            ?.setDuration(160L)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
        nextMonthView?.animate()
            ?.translationX(monthDragWidth())
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
            reloadVisibleEvents()
            return
        }
        dateSelected = false
        refreshCurrentEventList()
        val width = monthDragWidth()
        val currentStart = current.translationX
        val incomingStart = incoming.translationX
        val currentEnd = if (direction > 0) -width else width
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                current.translationX = currentStart + (currentEnd - currentStart) * progress
                incoming.translationX = incomingStart * (1f - progress)
                current.alpha = 1f
                incoming.alpha = 1f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    current.translationX = currentEnd
                    incoming.translationX = 0f
                    monthTransitionDirection = 0
                    visibleMonth = if (direction > 0) visibleMonth.plusMonths(1) else visibleMonth.minusMonths(1)
                    rotateMonthPages(direction)
                    redrawAdjacentMonthPages()
                    reloadVisibleEvents()
                }
            })
            start()
        }
    }

    private fun playListResizeTransition() {
        activeSwipeViews.forEach { view ->
            view.post {
                view.alpha = 0.9f
                view.animate()
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(140L)
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
                reloadVisibleEvents()
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
                events = loadVisibleEvents(currentUser.id)
            },
            done = {
                refreshWidgets()
                showCalendar()
            },
        )
    }

    private fun reloadVisibleEvents() {
        val currentUser = user ?: return
        if (calendars.isEmpty()) {
            reloadCalendar()
            return
        }
        val targetMonth = visibleMonth
        val cacheKey = visibleEventsCacheKey(targetMonth)
        visibleEventCache[cacheKey]?.let { cachedEvents ->
            events = cachedEvents
            redrawAdjacentMonthPages()
        }
        val requestVersion = ++eventLoadVersion
        background(
            work = {
                loadVisibleEvents(currentUser.id, targetMonth)
            },
            done = { loadedEvents ->
                if (requestVersion == eventLoadVersion && visibleMonth == targetMonth) {
                    events = loadedEvents
                    redrawAdjacentMonthPages()
                }
            },
        )
    }

    private fun loadVisibleEvents(userId: String, month: YearMonth = visibleMonth): List<EventItem> {
        val cacheKey = visibleEventsCacheKey(month)
        val eventRange = visibleEventRange(month)
        val loadedEvents = visibleCalendars().flatMap {
            CalendarApi.listEvents(it.id, userId, eventRange.second, eventRange.first)
        }
        visibleEventCache[cacheKey] = loadedEvents
        while (visibleEventCache.size > 8) {
            visibleEventCache.remove(visibleEventCache.keys.first())
        }
        return loadedEvents
    }

    private fun visibleEventsCacheKey(month: YearMonth = visibleMonth): String {
        val currentUserId = user?.id.orEmpty()
        return "${currentUserId}|${month}|${visibleCalendarIds.sorted().joinToString(",")}"
    }

    private fun clearVisibleEventCache() {
        visibleEventCache.clear()
        eventLoadVersion += 1
    }

    private fun visibleEventRange(month: YearMonth = visibleMonth): Pair<LocalDateTime, LocalDateTime> {
        val rangeStart = calendarGridStart(month.minusMonths(1)).atStartOfDay()
        val rangeEnd = calendarGridStart(month.plusMonths(1)).plusDays(42).atStartOfDay()
        return rangeStart to rangeEnd
    }

    private fun calendarGridView(): GridLayout {
        return GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
            background = null
            setPadding(0, 0, 0, 0)
        }
    }

    private fun calendarPage(grid: GridLayout, overlay: MultiDayTitleOverlay): FrameLayout {
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
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

    private fun redrawAdjacentMonthPages() {
        val currentGrid = currentCalendarGrid ?: return
        val previousGrid = previousCalendarGrid ?: return
        val nextGrid = nextCalendarGrid ?: return
        val previousMonth = visibleMonth.minusMonths(1)
        val nextMonth = visibleMonth.plusMonths(1)
        calendarEventCache.clear()
        multiDaySlotCache.clear()
        currentMonthTitle?.text = visibleMonth.format(monthFormatter)
        previousMonthOverlay?.setMonth(previousMonth)
        currentMonthOverlay?.setMonth(visibleMonth)
        nextMonthOverlay?.setMonth(nextMonth)
        drawCalendar(previousGrid, previousMonth)
        drawCalendar(currentGrid, visibleMonth)
        drawCalendar(nextGrid, nextMonth)
        refreshCurrentEventList()
        currentMonthView?.apply {
            translationX = 0f
            translationY = 0f
            scaleY = 1f
            alpha = 1f
        }
        previousMonthView?.alpha = 1f
        nextMonthView?.alpha = 1f
        prepareAdjacentMonthViews()
    }

    private fun rotateMonthPages(direction: Int) {
        val oldCurrentView = currentMonthView
        val oldPreviousView = previousMonthView
        val oldNextView = nextMonthView
        val oldCurrentGrid = currentCalendarGrid
        val oldPreviousGrid = previousCalendarGrid
        val oldNextGrid = nextCalendarGrid
        val oldCurrentOverlay = currentMonthOverlay
        val oldPreviousOverlay = previousMonthOverlay
        val oldNextOverlay = nextMonthOverlay

        if (direction > 0) {
            previousMonthView = oldCurrentView
            currentMonthView = oldNextView
            nextMonthView = oldPreviousView
            previousCalendarGrid = oldCurrentGrid
            currentCalendarGrid = oldNextGrid
            nextCalendarGrid = oldPreviousGrid
            previousMonthOverlay = oldCurrentOverlay
            currentMonthOverlay = oldNextOverlay
            nextMonthOverlay = oldPreviousOverlay
        } else {
            previousMonthView = oldNextView
            currentMonthView = oldPreviousView
            nextMonthView = oldCurrentView
            previousCalendarGrid = oldNextGrid
            currentCalendarGrid = oldPreviousGrid
            nextCalendarGrid = oldCurrentGrid
            previousMonthOverlay = oldNextOverlay
            currentMonthOverlay = oldPreviousOverlay
            nextMonthOverlay = oldCurrentOverlay
        }
        currentMonthView?.let { activeSwipeViews = listOfNotNull(it, currentListPanel) }
    }

    private fun selectCalendarDate(date: LocalDate) {
        val previousDate = selectedDate.takeIf { dateSelected }
        selectedDate = date
        dateSelected = true
        if (listHidden) {
            listHidden = false
            listExpanded = false
            monthTransitionDirection = 0
            showCalendar()
            return
        }
        val targetMonth = YearMonth.from(date)
        if (targetMonth == visibleMonth) {
            previousDate?.let { replaceCalendarCell(it) }
            replaceCalendarCell(date)
            refreshCurrentEventList()
        } else {
            visibleMonth = targetMonth
            showCalendar()
            reloadVisibleEvents()
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
            dayEvents = calendarEventsForDate(date),
            inMonth = date.month == month.month,
            today = date == LocalDate.now(),
            selected = dateSelected && date == selectedDate,
            sunday = date.dayOfWeek.value == 7 || holiday != null,
            saturday = date.dayOfWeek.value == 6,
        ).apply {
            setOnClickListener { selectCalendarDate(date) }
        }
    }

    private fun calendarEventsForDate(date: LocalDate): List<EventItem?> {
        calendarEventCache[date]?.let { return it }
        val weekStart = date.minusDays(date.dayOfWeek.value % 7L)
        val slotByEventId = multiDaySlotCache.getOrPut(weekStart) { multiDaySlotsForWeek(weekStart) }
        val dayEvents = eventsForDate(events, date)
        val activeMultiDayEvents = dayEvents.filter { it.isMultiDay() }
        val lastActiveSlot = activeMultiDayEvents
            .mapNotNull { slotByEventId[it.id] }
            .maxOrNull() ?: -1
        val slots = MutableList<EventItem?>(lastActiveSlot + 1) { null }
        val holidayReservesFirstSlot = holidayName(date) != null && slots.isNotEmpty()
        activeMultiDayEvents.forEach { event ->
            val slot = slotByEventId[event.id]
            if (slot != null && slot in slots.indices) slots[slot] = event
        }
        val singleDayEvents = dayEvents.filterNot { it.isMultiDay() }.toMutableList()
        val compactRows = slots.mapIndexed { index, event ->
            if (holidayReservesFirstSlot && index == 0 && event == null) null else event ?: singleDayEvents.removeFirstOrNull()
        } + singleDayEvents
        val visualRows = if (holidayReservesFirstSlot && compactRows.firstOrNull() == null) compactRows.drop(1) else compactRows
        return visualRows.also {
            calendarEventCache[date] = it
        }
    }

    private fun multiDaySlotsForWeek(weekStart: LocalDate): Map<String, Int> {
        val weekDates = (0..6).map { weekStart.plusDays(it.toLong()) }
        val weekMultiDayEvents = weekDates
            .flatMap { day -> eventsForDate(events, day).filter { it.isMultiDay() } }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<EventItem> { it.startsAt.toLocalDate() }
                    .thenBy { it.startsAt.toLocalTime() }
                    .thenBy { it.title }
            )
        val occupiedSlotsByDay = List(7) { day ->
            mutableSetOf<Int>().apply {
                if (holidayName(weekStart.plusDays(day.toLong())) != null) add(0)
            }
        }
        val slotByEventId = mutableMapOf<String, Int>()
        weekMultiDayEvents.forEach { event ->
            val eventStart = event.startsAt.toLocalDate()
            val eventEnd = event.endsAt?.toLocalDate() ?: eventStart
            val segmentStart = maxOf(eventStart, weekStart)
            val segmentEnd = minOf(eventEnd, weekStart.plusDays(6))
            val startCol = segmentStart.dayOfWeek.value % 7
            val endCol = segmentEnd.dayOfWeek.value % 7
            var slot = 0
            while ((startCol..endCol).any { day -> occupiedSlotsByDay[day].contains(slot) }) {
                slot += 1
            }
            for (day in startCol..endCol) {
                occupiedSlotsByDay[day].add(slot)
            }
            slotByEventId[event.id] = slot
        }
        return slotByEventId
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
        dayEvents: List<EventItem?>,
        inMonth: Boolean,
        today: Boolean,
        selected: Boolean,
        sunday: Boolean,
        saturday: Boolean,
    ): LinearLayout {
        val realEvents = dayEvents.filterNotNull()
        val cell = LinearLayout(this).vertical()
        cell.gravity = Gravity.START
        cell.setPadding(0, if (listExpanded) 3.dp() else 2.dp(), 0, if (listExpanded) 2.dp() else 1.dp())
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
            includeFontPadding = false
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
        val topPaddingDp = if (listExpanded) 3 else 2
        val bottomPaddingDp = if (listExpanded) 2 else 1
        val numberHeightDp = 14
        val normalRowHeightDp = 12
        cell.addView(number, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, numberHeightDp.dp()))

        if (listExpanded) {
            if (realEvents.isNotEmpty()) {
                val dots = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                realEvents.mapNotNull { ownerAccentColor(it.createdBy) }.distinct().take(4).forEach { color ->
                    dots.addView(View(this).apply {
                        background = rounded(color, 999.dp())
                    }, LinearLayout.LayoutParams(6.dp(), 6.dp()).apply {
                        leftMargin = 1.dp()
                        rightMargin = 1.dp()
                    })
                }
                cell.addView(dots, matchWrap(top = 2))
            }
        } else {
            val holidayHeightDp = if (holiday != null) normalRowHeightDp else 0
            if (holiday != null) {
                cell.addView(TextView(this).text(holiday).size(8).apply {
                    setTextColor(0xFFDC2626.toInt())
                    includeFontPadding = false
                    gravity = Gravity.START
                    setPadding(3.dp(), 0, 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (normalRowHeightDp - 1).dp()).apply {
                    topMargin = 1.dp()
                })
            }

            val availableEventHeight = (calendarCellHeight() - topPaddingDp - bottomPaddingDp - numberHeightDp - holidayHeightDp).coerceAtLeast(normalRowHeightDp)
            val rowHeightDp = normalRowHeightDp
            val childHeightDp = (rowHeightDp - 1).coerceAtLeast(7)
            val eventTextSize = 8
            val eventRows = ((availableEventHeight / rowHeightDp) + if (listHidden) 1 else 0).coerceIn(1, 10)
            val visibleEvents = if (realEvents.size > eventRows) dayEvents.take((eventRows - 1).coerceAtLeast(0)) else dayEvents.take(eventRows)
            val hiddenEventCount = (realEvents.size - visibleEvents.filterNotNull().size).coerceAtLeast(0)
            visibleEvents.forEach { eventOrNull ->
                if (eventOrNull == null) {
                    cell.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, childHeightDp.dp()).apply {
                        topMargin = 1.dp()
                    })
                    return@forEach
                }
                val event = eventOrNull
                    val multiDay = event.isMultiDay()
                    val segmentStart = multiDay && (event.startsAt.toLocalDate() == date || date.dayOfWeek.value == 7)
                    val weekStart = date.minusDays(date.dayOfWeek.value % 7L)
                    val title = when {
                        !multiDay -> event.title
                        else -> ""
                    }
                    cell.addView(eventChipView(event, title, eventTextSize, multiDay, segmentStart), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, childHeightDp.dp()).apply {
                        topMargin = 1.dp()
                        if (!multiDay || segmentStart) {
                            leftMargin = 3.dp()
                        }
                        if (!multiDay) {
                            rightMargin = 3.dp()
                        }
                    })
                }
            if (hiddenEventCount > 0) {
                cell.addView(TextView(this).text("+$hiddenEventCount").size(eventTextSize).apply {
                    setTextColor(slate600)
                    maxLines = 1
                    includeFontPadding = false
                    gravity = Gravity.START
                    setPadding(3.dp(), 0, 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, childHeightDp.dp()).apply {
                    topMargin = 1.dp()
                })
            }
        }
        return cell
    }

    private fun eventChipView(
        event: EventItem,
        title: String,
        textSize: Int,
        multiDay: Boolean,
        segmentStart: Boolean,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(softCalendarColor(calendarColor(event.calendarId)), if (multiDay) 0 else 4.dp())
            val accentColor = ownerAccentColor(event.createdBy)
            if (accentColor != null && (!multiDay || segmentStart)) {
                addView(View(this@MainActivity).apply {
                    background = rounded(accentColor, if (multiDay) 0 else 3.dp())
                }, LinearLayout.LayoutParams(3.dp(), LinearLayout.LayoutParams.MATCH_PARENT))
            }
            addView(TextView(this@MainActivity).text(title).size(textSize).apply {
                setTextColor(slate900)
                maxLines = 1
                setSingleLine(true)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(if (!multiDay || segmentStart) 2.dp() else 0, 0, if (multiDay) 0 else 3.dp(), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun ownerAccentColor(ownerId: String?): Int? {
        if (ownerId == null || ownerId == ALL_OWNER_ID) return null
        return NativeStore.ownerColor(this, ownerId) ?: ownerColor(ownerId)
    }

    private inner class MultiDayTitleOverlay(
        context: Context,
        private var month: YearMonth,
        private val grid: GridLayout,
    ) : View(context) {
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = slate900
            textSize = 8f * resources.displayMetrics.scaledDensity
        }

        init {
            isClickable = false
            isFocusable = false
        }

        fun setMonth(newMonth: YearMonth) {
            month = newMonth
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (listExpanded || width <= 0 || height <= 0) return
            val eventTopOffset = (if (listExpanded) 3 else 2).dp() + 14.dp()
            val rowHeight = 12.dp().toFloat()
            val childHeight = 11.dp().toFloat()
            val start = calendarGridStart(month)

            repeat(6) { row ->
                val weekStart = start.plusDays((row * 7).toLong())
                val slotByEventId = multiDaySlotsForWeek(weekStart)
                slotByEventId.entries.sortedBy { it.value }.forEach { (eventId, slot) ->
                    val event = events.firstOrNull { it.id == eventId } ?: return@forEach
                    val eventStart = event.startsAt.toLocalDate()
                    val eventEnd = event.endsAt?.toLocalDate() ?: eventStart
                    val segmentStart = maxOf(eventStart, weekStart)
                    val segmentEnd = minOf(eventEnd, weekStart.plusDays(6))
                    if (segmentEnd.isBefore(segmentStart)) return@forEach
                    val startCol = segmentStart.dayOfWeek.value % 7
                    val endCol = segmentEnd.dayOfWeek.value % 7
                    val startCell = grid.getChildAt(7 + row * 7 + startCol) ?: return@forEach
                    val endCell = grid.getChildAt(7 + row * 7 + endCol) ?: return@forEach
                    if (startCell.height <= 0 || endCell.height <= 0) return@forEach
                    val label = if (eventStart == segmentStart || segmentStart == weekStart) event.title else ""
                    if (label.isBlank()) return@forEach
                    val left = startCell.left.toFloat() + 6.dp()
                    val right = endCell.right.toFloat() - 4.dp()
                    val top = startCell.top.toFloat() + eventTopOffset + slot * rowHeight
                    val save = canvas.save()
                    canvas.clipRect(left + 5.dp(), top, right, top + childHeight)
                    canvas.drawText(label, left + 5.dp(), top + 9.dp(), titlePaint)
                    canvas.restoreToCount(save)
                }
            }
        }
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
                background = rounded(softCalendarColor(calendarColor(event.calendarId)), 10.dp(), 0xFFE2E8F0.toInt())
                setPadding(0, 10.dp(), 12.dp(), 10.dp())
                setOnClickListener { showEventDialog(event) }
            }
            ownerAccentColor(event.createdBy)?.let { accentColor ->
                row.addView(View(this).apply {
                    background = rounded(accentColor, 4.dp())
                }, LinearLayout.LayoutParams(5.dp(), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    rightMargin = 10.dp()
                })
            } ?: row.setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
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
        fun hasExplicitTime(item: EventItem): Boolean {
            val endTime = item.endsAt?.toLocalTime()
            return item.startsAt.toLocalTime() != LocalTime.MIDNIGHT ||
                (endTime != null && endTime != LocalTime.of(23, 59))
        }
        val eventHasTime = event?.let { hasExplicitTime(it) } == true
        var date = event?.startsAt?.toLocalDate() ?: selectedDate.takeIf { dateSelected } ?: LocalDate.now()
        var time = if (event == null || eventHasTime) event?.startsAt?.toLocalTime() ?: LocalTime.of(9, 0) else LocalTime.of(9, 0)
        var endDate = event?.endsAt?.toLocalDate() ?: date
        var endTime = if (event == null || eventHasTime) event?.endsAt?.toLocalTime() ?: time.plusHours(1) else time.plusHours(1)

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
            isChecked = eventHasTime
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
            textSize = 13f
            minLines = 4
            maxLines = 6
            minHeight = 112.dp()
            gravity = Gravity.TOP or Gravity.START
            setText(event?.body.orEmpty())
        })
        val locationInput = styleInput(EditText(this).apply {
            hint = "장소"
            textSize = 13f
            setSingleLine(true)
            setText(event?.location.orEmpty())
        })
        bodyInput.textSize = 13f
        locationInput.textSize = 13f

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
        timeCheck.setOnCheckedChangeListener { _, checked ->
            if (checked && time == LocalTime.MIDNIGHT) time = LocalTime.of(9, 0)
            if (checked && (endTime == LocalTime.MIDNIGHT || endTime == LocalTime.of(23, 59))) endTime = time.plusHours(1)
            refreshButtons()
        }
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
            addView(bodyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 112.dp()).apply {
                topMargin = 8.dp()
            })
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
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
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
                saveButton.isEnabled = false
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
                        clearVisibleEventCache()
                        reloadCalendar()
                    },
                    failed = {
                        saveButton.isEnabled = true
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
                                    clearVisibleEventCache()
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

    private fun dialogPanel(): LinearLayout {
        return LinearLayout(this).vertical().apply {
            setPadding(18.dp(), 18.dp(), 18.dp(), 12.dp())
            background = rounded(0xFFF8FAFC.toInt(), 16.dp(), 0xFFE2E8F0.toInt())
        }
    }

    private fun dialogInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setTextColor(slate900)
            setHintTextColor(0xFF94A3B8.toInt())
            textSize = 15f
            background = rounded(Color.WHITE, 10.dp(), 0xFFCBD5E1.toInt())
            setPadding(12.dp(), 0, 12.dp(), 0)
            minHeight = 48.dp()
        }
    }

    private fun dialogRow(title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).vertical().apply {
            background = rounded(Color.WHITE, 12.dp(), 0xFFE2E8F0.toInt())
            setPadding(14.dp(), 11.dp(), 14.dp(), 11.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).text(title).size(15).bold().apply {
                setTextColor(slate900)
                maxLines = 1
            }, matchWrap())
            if (subtitle.isNotBlank()) {
                addView(TextView(this@MainActivity).text(subtitle).size(12).apply {
                    setTextColor(slate600)
                    maxLines = 2
                }, matchWrap(top = 3))
            }
        }
    }

    private fun showSearchDialog() {
        val currentUser = user ?: return
        if (calendars.isEmpty()) {
            toast("검색할 달력이 없습니다.")
            return
        }
        val searchCalendarIds = visibleCalendars().map { it.id }.toMutableSet()
        val input = dialogInput("검색어").apply {
            hint = "검색어"
            setSingleLine(true)
        }
        val resultList = LinearLayout(this).vertical()
        val resultCaption = TextView(this).text("검색어를 입력하고 조회하세요.").size(12).apply {
            setTextColor(slate600)
        }
        val calendarLabel = TextView(this).text(calendarLabel(searchCalendarIds)).size(14).muted().apply {
            background = rounded(0xFFEFF6FF.toInt(), 999.dp())
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        }
        val content = dialogPanel()
        content.addView(TextView(this).text("일정 검색").size(20).bold().apply {
            setTextColor(slate900)
        }, matchWrap())
        content.addView(calendarLabel, matchWrap(top = 10))
        calendarLabel.setOnClickListener {
            val checked = calendars.map { it.id in searchCalendarIds }.toBooleanArray()
            AlertDialog.Builder(this)
                .setTitle("검색할 달력")
                .setMultiChoiceItems(calendars.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton("취소", null)
                .setPositiveButton("적용") { _, _ ->
                    searchCalendarIds.clear()
                    searchCalendarIds.addAll(calendars.filterIndexed { index, _ -> checked[index] }.map { it.id })
                    calendarLabel.text = calendarLabel(searchCalendarIds)
                }
                .show()
        }
        content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 58.dp()).apply {
            topMargin = 14.dp()
        })
        val searchButton = Button(this).apply {
            text = "검색"
            primaryButton()
        }
        content.addView(searchButton, matchWrap(top = 10))
        content.addView(resultCaption, matchWrap(top = 14))
        content.addView(resultList, matchWrap(top = 6))
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("닫기", null)
            .create()
        fun renderResults(results: List<EventItem>) {
            resultList.removeAllViews()
            resultCaption.text = if (results.isEmpty()) "검색 결과가 없습니다." else "${results.size}개 일정"
            results.forEach { event ->
                val score = event.similarity?.let { "유사도 ${"%.2f".format(it)}" }
                val meta = listOfNotNull(event.startsAt.toLocalDate().format(dateFormatter), score).joinToString("  ·  ")
                resultList.addView(dialogRow(event.title, meta) {
                    selectedDate = event.startsAt.toLocalDate()
                    visibleMonth = YearMonth.from(selectedDate)
                    dateSelected = true
                    listExpanded = false
                    listHidden = false
                    dialog.dismiss()
                    showCalendar()
                    reloadVisibleEvents()
                }, matchWrap(top = 8))
            }
        }
        searchButton.setOnClickListener {
            val query = input.text.toString().trim()
            when {
                query.isBlank() -> toast("검색어를 입력해 주세요.")
                searchCalendarIds.isEmpty() -> toast("검색할 달력을 선택해 주세요.")
                else -> {
                    hideKeyboard(input)
                    resultCaption.text = "검색 중..."
                    resultList.removeAllViews()
                    background(
                        work = { CalendarApi.searchEvents(searchCalendarIds.toList(), query, currentUser.id, NativeStore.searchMaxDistance(this)) },
                        done = { renderResults(it) },
                    )
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        input.postDelayed({
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_FORCED)
        }, 200)
    }

    private fun showSearchResults(results: List<EventItem>) {
        if (results.isEmpty()) {
            toast("검색 결과가 없습니다.")
            return
        }
        val content = dialogPanel()
        content.addView(TextView(this).text("검색 결과").size(20).bold().apply {
            setTextColor(slate900)
        }, matchWrap())
        content.addView(TextView(this).text("${results.size}개 일정").size(12).apply {
            setTextColor(slate600)
        }, matchWrap(top = 4))
        val resultList = LinearLayout(this).vertical()
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("닫기", null)
            .create()
        results.forEach { event ->
            val score = event.similarity?.let { "유사도 ${"%.2f".format(it)}" }
            val meta = listOfNotNull(event.startsAt.toLocalDate().format(dateFormatter), score).joinToString("  ·  ")
            resultList.addView(dialogRow(event.title, meta) {
                selectedDate = event.startsAt.toLocalDate()
                visibleMonth = YearMonth.from(selectedDate)
                dateSelected = true
                listExpanded = false
                listHidden = false
                dialog.dismiss()
                showCalendar()
                reloadVisibleEvents()
            }, matchWrap(top = 8))
        }
        content.addView(resultList, matchWrap(top = 12))
        dialog.show()
    }

    private fun calendarLabel(calendarIds: Set<String>): String {
        val selected = calendars.filter { it.id in calendarIds }
        return when {
            selected.isEmpty() -> "검색할 달력 선택"
            selected.size == 1 -> selected.first().name
            else -> selected.joinToString(" · ") { it.name }
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showCalendarDialog() {
        val currentUser = user ?: return
        val content = dialogPanel()
        content.addView(TextView(this).text("설정").size(20).bold().apply {
            setTextColor(slate900)
        }, matchWrap())
        content.addView(TextView(this).text("표시할 달력과 기본 등록 달력을 한 번에 정합니다.").size(12).apply {
            setTextColor(slate600)
        }, matchWrap(top = 4))
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("닫기", null)
            .create()
        val checkedIds = visibleCalendars().map { it.id }.toMutableSet()
        var defaultId = selectedCalendarId ?: calendars.firstOrNull()?.id
        val radioButtons = mutableListOf<RadioButton>()
        fun persistCalendarSelection() {
            selectedCalendarId = defaultId?.takeIf { id -> calendars.any { it.id == id } }
            if (selectedCalendarId == null || selectedCalendarId !in checkedIds) selectedCalendarId = checkedIds.firstOrNull()
            visibleCalendarIds = checkedIds
            NativeStore.saveVisibleCalendarIds(this, visibleCalendarIds)
            NativeStore.saveDefaultCalendarId(this, selectedCalendarId)
            reloadCalendar()
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).text("내 달력").size(13).bold().apply {
                setTextColor(slate900)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).text("길게 누르면 초대코드 복사").size(11).apply {
                setTextColor(slate600)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, matchWrap(top = 14))
        calendars.forEach { calendar ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.WHITE, 12.dp(), 0xFFE2E8F0.toInt())
                setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                setOnLongClickListener {
                    copyInviteCode(calendar)
                    true
                }
            }
            val check = CheckBox(this).apply {
                isChecked = calendar.id in checkedIds
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        checkedIds.add(calendar.id)
                    } else if (checkedIds.size <= 1) {
                        isChecked = true
                        toast("하나 이상 선택해 주세요.")
                        return@setOnCheckedChangeListener
                    } else {
                        checkedIds.remove(calendar.id)
                        if (defaultId == calendar.id) {
                            defaultId = checkedIds.first()
                            radioButtons.forEach { it.isChecked = false }
                        }
                    }
                    persistCalendarSelection()
                }
            }
            val texts = LinearLayout(this).vertical()
            texts.addView(TextView(this).text(calendar.name).size(15).bold().apply {
                setTextColor(slate900)
                maxLines = 1
            }, matchWrap())
            val radio = RadioButton(this).apply {
                isChecked = calendar.id == defaultId
                setOnClickListener {
                    defaultId = calendar.id
                    radioButtons.forEach { it.isChecked = false }
                    isChecked = true
                    checkedIds.add(calendar.id)
                    check.isChecked = true
                    persistCalendarSelection()
                }
            }
            radioButtons.add(radio)
            row.addView(check, LinearLayout.LayoutParams(42.dp(), 42.dp()))
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(radio, LinearLayout.LayoutParams(42.dp(), 42.dp()))
            content.addView(row, matchWrap(top = 8))
        }
        fun add(title: String, subtitle: String, action: () -> Unit) {
            content.addView(dialogRow(title, subtitle) {
                dialog.dismiss()
                action()
            }, matchWrap(top = 8))
        }
        add("내 일정 색상", "달력 일정 앞쪽에 표시되는 내 색상") { showMyOwnerColorDialog(returnToSettings = true) }
        add("검색 임계치 설정", NativeStore.searchMaxDistance(this).toString()) { showSearchThresholdDialog(returnToSettings = true) }
        add("ICS \uAC00\uC838\uC624\uAE30", "\uB124\uC774\uBC84 \uCE98\uB9B0\uB354 \uBC31\uC5C5 \uD30C\uC77C\uC744 \uD55C \uBC88\uB9CC \uAC00\uC838\uC635\uB2C8\uB2E4") { openIcsImportPicker() }
        add("\uBE44\uBC00\uBC88\uD638 \uBCC0\uACBD", "\uD604\uC7AC \uBE44\uBC00\uBC88\uD638\uB85C \uD655\uC778 \uD6C4 \uBCC0\uACBD") { showChangePasswordDialog(returnToSettings = true) }
        add("\uB85C\uADF8\uC544\uC6C3", "\uC774 \uAE30\uAE30\uC758 \uC790\uB3D9 \uB85C\uADF8\uC778\uC744 \uD574\uC81C") { confirmLogout() }
        add("초대코드로 참여", "가족 달력에 참여") { showJoinCalendarDialog(currentUser, returnToSettings = true) }
        add("새 달력 만들기", "공유할 달력 추가") { showCreateCalendarDialog(currentUser, returnToSettings = true) }
        dialog.show()
    }
    private fun showMyOwnerColorDialog(returnToSettings: Boolean = false) {
        val currentUser = user ?: return
        val content = LinearLayout(this).vertical().apply {
            setPadding(18.dp(), 10.dp(), 18.dp(), 4.dp())
        }
        content.addView(TextView(this).text("${currentUser.displayName} 일정 색상").size(16).bold().apply {
            setTextColor(slate900)
        }, matchWrap())
        val selected = NativeStore.ownerColor(this, currentUser.id) ?: ownerColor(currentUser.id)
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("취소") { _, _ -> if (returnToSettings) showCalendarDialog() }
            .create()
        ownerPalette.chunked(4).forEach { rowColors ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowColors.forEach { color ->
                row.addView(TextView(this).text(if (color == selected) "✓" else "").center().apply {
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = rounded(color, 999.dp(), if (color == selected) slate900 else Color.TRANSPARENT, if (color == selected) 2.dp() else 0)
                    setOnClickListener {
                        NativeStore.saveOwnerColor(this@MainActivity, currentUser.id, color)
                        dialog.dismiss()
                        showCalendar()
                        if (returnToSettings) showCalendarDialog()
                    }
                }, LinearLayout.LayoutParams(44.dp(), 44.dp()).apply {
                    leftMargin = 6.dp()
                    rightMargin = 6.dp()
                    topMargin = 12.dp()
                })
            }
            content.addView(row, matchWrap())
        }
        dialog.setOnCancelListener { if (returnToSettings) showCalendarDialog() }
        dialog.show()
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
        copyInviteCode(selected)
    }

    private fun copyInviteCode(calendar: CalendarItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("calendar invite code", calendar.inviteCode))
        toast("초대코드를 복사했습니다.")
    }

    private fun showSearchThresholdDialog(returnToSettings: Boolean = false) {
        val input = dialogInput("0.2").apply {
            setSingleLine(true)
            setText(NativeStore.searchMaxDistance(this@MainActivity).toString())
        }
        val content = dialogPanel().apply {
            addView(TextView(this@MainActivity).text("검색 임계치").size(20).bold().apply {
                setTextColor(slate900)
            }, matchWrap())
            addView(TextView(this@MainActivity).text("낮을수록 더 비슷한 일정만 검색됩니다.").size(12).apply {
                setTextColor(slate600)
            }, matchWrap(top = 4))
            addView(input, matchWrap(top = 14))
        }
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("취소") { _, _ -> if (returnToSettings) showCalendarDialog() }
            .setPositiveButton("저장") { _, _ ->
                val value = input.text.toString().toDoubleOrNull()
                if (value == null) {
                    toast("숫자로 입력해 주세요.")
                } else {
                    NativeStore.saveSearchMaxDistance(this, value)
                    toast("검색 임계치를 저장했습니다.")
                }
            }
            .create()
        dialog.setOnCancelListener { if (returnToSettings) showCalendarDialog() }
        dialog.show()
    }

    private fun openIcsImportPicker() {
        if (calendars.isEmpty()) {
            toast("\uAC00\uC838\uC62C \uCE98\uB9B0\uB354\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/calendar", "application/octet-stream", "text/*"))
        }
        startActivityForResult(intent, ICS_IMPORT_REQUEST)
    }

    private fun importIcsFromUri(uri: Uri) {
        val calendar = calendars.find { it.id == selectedCalendarId } ?: visibleCalendars().firstOrNull() ?: calendars.firstOrNull()
        if (calendar == null) {
            toast("\uAC00\uC838\uC62C \uCE98\uB9B0\uB354\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.")
            return
        }
        val parsed = try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            parseIcsEvents(text)
        } catch (error: Exception) {
            toast(error.message ?: "\uD30C\uC77C\uC744 \uC77D\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.")
            return
        }
        if (parsed.isEmpty()) {
            toast("\uAC00\uC838\uC62C \uC77C\uC815\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("ICS \uAC00\uC838\uC624\uAE30")
            .setMessage("${calendar.name}\uC5D0 ${parsed.size}\uAC1C \uC77C\uC815\uC744 \uAC00\uC838\uC62C\uAE4C\uC694?\n\uC911\uBCF5\uB41C \uC77C\uC815\uC740 \uAC74\uB108\uB701\uB2C8\uB2E4.")
            .setNegativeButton("\uCDE8\uC18C", null)
            .setPositiveButton("\uAC00\uC838\uC624\uAE30") { _, _ ->
                importIcsEvents(calendar, parsed)
            }
            .show()
    }

    private fun importIcsEvents(calendar: CalendarItem, parsed: List<IcsImportEvent>) {
        val currentUser = user ?: return
        background(
            work = {
                val existingKeys = events.map { importKey(it.title, it.startsAt, it.endsAt) }.toMutableSet()
                var imported = 0
                var skipped = 0
                parsed.forEach { item ->
                    val key = importKey(item.title, item.startsAt, item.endsAt)
                    if (key in existingKeys) {
                        skipped += 1
                    } else {
                        CalendarApi.createEvent(
                            calendar.id,
                            currentUser.id,
                            item.title,
                            item.body,
                            item.location,
                            item.startsAt,
                            item.endsAt,
                            currentUser.id,
                            item.recurrenceRule,
                        )
                        existingKeys.add(key)
                        imported += 1
                    }
                }
                imported to skipped
            },
            done = { (imported, skipped) ->
                toast("\uAC00\uC838\uC624\uAE30 \uC644\uB8CC: ${imported}\uAC1C, \uC911\uBCF5 ${skipped}\uAC1C")
                clearVisibleEventCache()
                reloadCalendar()
            },
        )
    }

    private fun parseIcsEvents(text: String): List<IcsImportEvent> {
        val lines = unfoldIcsLines(text)
        val items = mutableListOf<Map<String, IcsProperty>>()
        var current: MutableMap<String, IcsProperty>? = null
        lines.forEach { line ->
            when (line.trim()) {
                "BEGIN:VEVENT" -> current = mutableMapOf()
                "END:VEVENT" -> {
                    current?.let { items.add(it.toMap()) }
                    current = null
                }
                else -> {
                    val target = current ?: return@forEach
                    val property = parseIcsProperty(line) ?: return@forEach
                    target[property.name] = property
                }
            }
        }
        return items.mapNotNull { props ->
            val startProp = props["DTSTART"] ?: return@mapNotNull null
            val title = props["SUMMARY"]?.value?.icsUnescape()?.trim().orEmpty().ifBlank { "\uC81C\uBAA9 \uC5C6\uB294 \uC77C\uC815" }
            val allDay = startProp.params.any { it.equals("VALUE=DATE", ignoreCase = true) }
            val startsAt = parseIcsDateTime(startProp.value, allDay) ?: return@mapNotNull null
            val endsAt = props["DTEND"]?.let { endProp ->
                if (allDay) {
                    parseIcsDate(endProp.value)?.minusDays(1)?.atTime(23, 59)?.takeIf { it.isAfter(startsAt) }
                } else {
                    parseIcsDateTime(endProp.value, false)?.takeIf { it != startsAt }
                }
            }
            IcsImportEvent(
                title = title,
                body = props["DESCRIPTION"]?.value?.icsUnescape().orEmpty(),
                location = props["LOCATION"]?.value?.icsUnescape().orEmpty(),
                startsAt = startsAt,
                endsAt = endsAt,
                recurrenceRule = props["RRULE"]?.value?.let { parseIcsRRule(it, startsAt.toLocalDate()) },
            )
        }
    }

    private data class IcsProperty(val name: String, val params: List<String>, val value: String)

    private fun unfoldIcsLines(text: String): List<String> {
        val result = mutableListOf<String>()
        text.replace("\r\n", "\n").replace("\r", "\n").lines().forEach { rawLine ->
            if ((rawLine.startsWith(" ") || rawLine.startsWith("\t")) && result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + rawLine.drop(1)
            } else if (rawLine.isNotBlank()) {
                result.add(rawLine)
            }
        }
        return result
    }

    private fun parseIcsProperty(line: String): IcsProperty? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val key = line.substring(0, colon)
        val parts = key.split(';')
        return IcsProperty(parts.first().uppercase(), parts.drop(1), line.substring(colon + 1))
    }

    private fun parseIcsDateTime(value: String, allDay: Boolean): LocalDateTime? {
        if (allDay) return parseIcsDate(value)?.atStartOfDay()
        val normalized = value.trim()
        return try {
            if (normalized.endsWith("Z")) {
                val instant = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").parse(normalized))
                LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            } else {
                LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIcsDate(value: String): LocalDate? {
        return try {
            LocalDate.parse(value.trim(), DateTimeFormatter.BASIC_ISO_DATE)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIcsRRule(value: String, startDate: LocalDate): JSONObject? {
        val parts = value.split(';').mapNotNull {
            val index = it.indexOf('=')
            if (index <= 0) null else it.substring(0, index).uppercase() to it.substring(index + 1)
        }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> JSONObject().put("frequency", "daily").put("interval", interval)
            "WEEKLY" -> JSONObject()
                .put("frequency", "weekly")
                .put("interval", interval.coerceIn(1, 3))
                .put("weekdays", JSONArray(parts["BYDAY"]?.split(',')?.mapNotNull { icsWeekday(it) } ?: listOf(startDate.appWeekday())))
            "MONTHLY" -> JSONObject()
                .put("frequency", "monthly")
                .put("interval", interval)
                .put("mode", "monthDay")
                .put("monthDay", startDate.dayOfMonth)
                .put("weekOfMonth", ((startDate.dayOfMonth - 1) / 7) + 1)
                .put("weekday", startDate.appWeekday())
            "YEARLY" -> JSONObject()
                .put("frequency", "yearly")
                .put("interval", interval)
                .put("lunar", false)
                .put("lunarMonth", startDate.monthValue)
                .put("lunarDay", startDate.dayOfMonth)
            else -> null
        }
    }

    private fun icsWeekday(value: String): Int? {
        val day = value.takeLast(2).uppercase()
        return when (day) {
            "SU" -> 0
            "MO" -> 1
            "TU" -> 2
            "WE" -> 3
            "TH" -> 4
            "FR" -> 5
            "SA" -> 6
            else -> null
        }
    }

    private fun LocalDate.appWeekday(): Int = if (dayOfWeek.value == 7) 0 else dayOfWeek.value

    private fun String.icsUnescape(): String {
        return replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
    }

    private fun importKey(title: String, startsAt: LocalDateTime, endsAt: LocalDateTime?): String {
        return "${title.filterNot { it.isWhitespace() }.lowercase()}|${startsAt.toLocalDate()}"
    }

    private fun showChangePasswordDialog(returnToSettings: Boolean = false) {
        val currentInput = EditText(this).apply {
            hint = "\uD604\uC7AC \uBE44\uBC00\uBC88\uD638"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newInput = EditText(this).apply {
            hint = "\uC0C8 \uBE44\uBC00\uBC88\uD638"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val content = dialogPanel().apply {
            addView(TextView(this@MainActivity).text("\uBE44\uBC00\uBC88\uD638 \uBCC0\uACBD").size(20).bold().apply {
                setTextColor(slate900)
            }, matchWrap())
            addView(currentInput, matchWrap(top = 14))
            addView(newInput, matchWrap(top = 10))
        }
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("\uCDE8\uC18C") { _, _ -> if (returnToSettings) showCalendarDialog() }
            .setPositiveButton("\uBCC0\uACBD", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = currentInput.text.toString()
                val newPassword = newInput.text.toString()
                if (current.isBlank() || newPassword.isBlank()) {
                    toast("\uBE44\uBC00\uBC88\uD638\uB97C \uC785\uB825\uD574 \uC8FC\uC138\uC694.")
                    return@setOnClickListener
                }
                background(
                    work = { CalendarApi.changePassword(current, newPassword) },
                    done = {
                        toast("\uBE44\uBC00\uBC88\uD638\uB97C \uBCC0\uACBD\uD588\uC2B5\uB2C8\uB2E4.")
                        dialog.dismiss()
                        if (returnToSettings) showCalendarDialog()
                    },
                )
            }
        }
        dialog.setOnCancelListener { if (returnToSettings) showCalendarDialog() }
        dialog.show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("\uB85C\uADF8\uC544\uC6C3")
            .setMessage("\uC774 \uAE30\uAE30\uC5D0\uC11C \uC790\uB3D9 \uB85C\uADF8\uC778\uC744 \uD574\uC81C\uD560\uAE4C\uC694?")
            .setNegativeButton("\uCDE8\uC18C", null)
            .setPositiveButton("\uB85C\uADF8\uC544\uC6C3") { _, _ ->
                background(
                    work = {
                        try {
                            CalendarApi.logout()
                        } finally {
                            NativeStore.clearSession(this)
                            CalendarApi.accessToken = null
                        }
                    },
                    done = {
                        user = null
                        showLogin()
                    },
                    failed = {
                        NativeStore.clearSession(this)
                        CalendarApi.accessToken = null
                        user = null
                        showLogin()
                    },
                )
            }
            .show()
    }

    private fun showCreateCalendarDialog(currentUser: User, returnToSettings: Boolean = false) {
        val input = EditText(this).apply {
            hint = "달력 이름"
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("새 달력 만들기")
            .setView(input)
            .setNegativeButton("취소") { _, _ -> if (returnToSettings) showCalendarDialog() }
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
            .create()
        dialog.setOnCancelListener { if (returnToSettings) showCalendarDialog() }
        dialog.show()
    }

    private fun showJoinCalendarDialog(currentUser: User, returnToSettings: Boolean = false) {
        val input = EditText(this).apply {
            hint = "초대 코드"
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("달력 참여")
            .setView(input)
            .setNegativeButton("취소") { _, _ -> if (returnToSettings) showCalendarDialog() }
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
            .create()
        dialog.setOnCancelListener { if (returnToSettings) showCalendarDialog() }
        dialog.show()
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
            } catch (error: AuthExpiredException) {
                runOnUiThread {
                    NativeStore.clearSession(this)
                    CalendarApi.accessToken = null
                    user = null
                    toast("로그인이 만료되었습니다.")
                    showLogin()
                }
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
private val ownerPalette = listOf(
    0xFF2563EB.toInt(),
    0xFFDB2777.toInt(),
    0xFFF59E0B.toInt(),
    0xFF7C3AED.toInt(),
    0xFF0891B2.toInt(),
    0xFF16A34A.toInt(),
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
private fun ownerColor(ownerId: String?): Int {
    if (ownerId == null || ownerId == ALL_OWNER_ID) return 0xFF64748B.toInt()
    val index = kotlin.math.abs(ownerId.hashCode()) % ownerPalette.size
    return ownerPalette[index]
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
