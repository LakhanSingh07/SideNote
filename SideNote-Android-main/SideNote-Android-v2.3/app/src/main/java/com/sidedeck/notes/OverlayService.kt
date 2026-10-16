package com.sidedeck.notes

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Premium floating notes overlay engine:
 * - Edge-anchored compact Peek Deck with adaptive sizing and internal scrolling
 * - Authentic sticky-note aesthetic in 6 curated pastel hues
 * - Mutually exclusive window states (Closed, Expanded, Minimized) with zero ghost windows
 * - Smooth 2D dragging with magnetic edge snapping
 * - Compact minimized chips (~96dp)
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var store: NotesStore
    private var handle: View? = null
    private var handleStrip: FrameLayout? = null
    private var handleImage: ImageView? = null
    private var handleCloseBadge: TextView? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var isHandleCloseActive = false
    private var panel: View? = null

    enum class WindowMode { EXPANDED, MINIMIZED }
    private data class FloatingWindow(
        val noteId: String,
        var mode: WindowMode,
        val view: View,
        val lp: WindowManager.LayoutParams
    )

    private val floatingCards = linkedMapOf<String, FloatingWindow>()
    private val maxFloatingCards = 4
    private val handler = Handler(Looper.getMainLooper())
    private var flushDraft: (() -> Boolean)? = null
    private var editedFloatingNoteId: String? = null
    private var shuttingDown = false
    private var overlayEditorVoiceReceiver: android.content.BroadcastReceiver? = null

    private fun safeNotes(): List<Note>? = try {
        store.all()
    } catch (_: IllegalStateException) {
        Toast.makeText(this, "Notes need recovery. Open SideNote and export your data before editing.", Toast.LENGTH_LONG).show()
        null
    }

    private val syncUpdates=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){
        if(shuttingDown || flushDraft!=null || !::store.isInitialized)return
        val showDeck=panel!=null
        val windows=floatingCards.map { (id,w)->Triple(id,w.mode,w.lp.x to w.lp.y) }
        if(showDeck)closePanel()
        dismissAllFloatingCards()
        val notes=safeNotes() ?: return
        windows.forEach { (id,mode,position)->notes.firstOrNull {it.id==id}?.let {if(mode==WindowMode.MINIMIZED)openMinimizedChip(it,position)else openExpandedNote(it,position)} }
        if(showDeck)showPanel()
    }}
    private val channel = "sidenote_overlay"
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_APPEARANCE && ::wm.isInitialized && ::store.isInitialized && !shuttingDown) {
            if (!closePanel()) return START_STICKY
            val windows = floatingCards.map { (id, w) -> Triple(id, w.mode, w.lp.x to w.lp.y) }
            dismissAllFloatingCards()
            Ui.configure(this)
            windows.forEach { (id, mode, xy) ->
                if (!shuttingDown) {
                    safeNotes()?.firstOrNull { it.id == id }?.let {
                        if (mode == WindowMode.MINIMIZED) openMinimizedChip(it, xy)
                        else openExpandedNote(it, xy)
                    }
                }
            }
        }
        if (intent?.action == ACTION_REMINDER_ALERT && ::wm.isInitialized && ::store.isInitialized && !shuttingDown) {
            val rId = intent.getStringExtra("reminder_note_id")
            if (rId != null) {
                safeNotes()?.firstOrNull { it.id == rId }?.let {
                    openExpandedNote(it)
                }
            }
        }
        if (handle == null && !shuttingDown && ::wm.isInitialized) {
            createHandle()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure floating sidebar handle remains active even when the app is swiped away from recent tasks
        try {
            val restartIntent = Intent(applicationContext, OverlayService::class.java).apply {
                setPackage(packageName)
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext, 201, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.set(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 500,
                restartPendingIntent
            )
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_REFRESH_APPEARANCE = "com.sidedeck.notes.REFRESH_APPEARANCE"
        const val ACTION_REMINDER_ALERT = "com.sidedeck.notes.ACTION_REMINDER_ALERT"
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        Ui.configure(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = NotesStore(this)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "Floating sidebar", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("SideNote is ready").setContentText("Tap the edge notch to view notes")
            .setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(13, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(13, notification)
        LanSyncManager.startBackgroundListener(this)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(syncUpdates,IntentFilter(LanSyncManager.ACTION_SYNC_UPDATED),RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(syncUpdates,IntentFilter(LanSyncManager.ACTION_SYNC_UPDATED))
        createHandle()
    }

    private fun params(w: Int, h: Int, gravity: Int, focus: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        if (focus) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity; if (focus) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    private fun safeRemoveView(v: View?) {
        if (v == null) return
        v.animate().cancel()
        try {
            if (v.isAttachedToWindow) wm.removeViewImmediate(v)
            else wm.removeView(v)
        } catch (_: Exception) {
            try { wm.removeView(v) } catch (_: Exception) {}
        }
    }

    private fun getHandleDrawable(right: Boolean, isClose: Boolean = false): android.graphics.drawable.GradientDrawable {
        val r = Ui.dp(this, 14).toFloat()
        val radii = if (isClose) {
            floatArrayOf(r, r, r, r, r, r, r, r)
        } else if (right) {
            // Docked on right edge: rounded corners on left, flush with screen bezel on right
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            // Docked on left edge: flush with screen bezel on left, rounded corners on right
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        val handlePurple = if (Ui.dark) Color.rgb(130, 65, 240) else Color.rgb(138, 75, 255)
        val strokeColor = if (Ui.dark) Color.rgb(95, 40, 195) else Color.rgb(115, 55, 225)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(if (isClose) Color.rgb(220, 55, 55) else handlePurple)
            cornerRadii = radii
            setStroke(Ui.dp(this@OverlayService, 1), if (isClose) Color.WHITE else strokeColor)
        }
    }

    private fun getTapeDrawable(right: Boolean): android.graphics.drawable.GradientDrawable {
        val r = Ui.dp(this, 14).toFloat()
        val radii = if (right) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(0f, 0f, r, r, 0f, 0f, 0f, 0f)
        }
        val tapeColor = if (Ui.dark) Color.rgb(105, 45, 210) else Color.rgb(120, 55, 235)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(tapeColor)
            cornerRadii = radii
        }
    }

    /** Transform handle strip into a red ✕ close target when dragging near it. */
    private fun showHandleCloseState(closeActive: Boolean) {
        if (isHandleCloseActive == closeActive) return
        isHandleCloseActive = closeActive
        val strip = handleStrip ?: return
        val img = handleImage ?: return
        val badge = handleCloseBadge ?: return
        if (closeActive) {
            img.setColorFilter(Color.argb(190, 220, 55, 55))
            badge.visibility = View.VISIBLE
            strip.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
        } else {
            img.clearColorFilter()
            badge.visibility = View.GONE
            strip.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun updateHandleDock(right: Boolean) {
        val touch = handle as? FrameLayout ?: return
        val strip = handleStrip ?: return
        val lp = handleLp ?: return
        val stripW = Ui.dp(this, 24)
        val stripH = Ui.dp(this, 94)
        val stripLp = FrameLayout.LayoutParams(stripW, stripH,
            Gravity.CENTER_VERTICAL or if (right) Gravity.RIGHT else Gravity.LEFT)
        strip.layoutParams = stripLp
        handleImage?.scaleX = if (right) -1f else 1f
        lp.gravity = (if (right) Gravity.END else Gravity.START) or Gravity.TOP
        runCatching { wm.updateViewLayout(touch, lp) }
    }

    /** Prominent 3D authentic edge tab handle movable across screen. */
    private fun createHandle() {
        val prefs = getSharedPreferences("overlay_ui", MODE_PRIVATE)
        var right = prefs.getBoolean("dock_right", false)
        val touch = FrameLayout(this).apply {
            contentDescription = "SideNote edge handle. Tap to peek at notes; drag vertically or across to move."
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        // ── 3D authentic side tab handle ────────────────────────────
        val strip = FrameLayout(this).apply {
            elevation = Ui.dp(this@OverlayService, 6).toFloat()
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_side_handle)
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = true
            scaleX = if (right) -1f else 1f
        }
        handleImage = img
        strip.addView(img, FrameLayout.LayoutParams(-1, -1))

        // Small close badge (hidden by default, shown in close-state)
        val gripTv = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = Ui.bg(Color.rgb(220, 55, 55), Ui.dp(this@OverlayService, 14).toFloat())
            visibility = View.GONE
        }
        strip.addView(gripTv, FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER))
        handleStrip = strip
        handleCloseBadge = gripTv

        val stripW = Ui.dp(this, 24)
        val stripH = Ui.dp(this, 94)
        val stripLp = FrameLayout.LayoutParams(stripW, stripH,
            Gravity.CENTER_VERTICAL or if (right) Gravity.RIGHT else Gravity.LEFT)
        touch.addView(strip, stripLp)

        val tabHeight = Ui.dp(this, 100)
        val touchWidth = Ui.dp(this, 50)
        val lp = params(touchWidth, tabHeight,
            (if (right) Gravity.END else Gravity.START) or Gravity.TOP).apply {
            y = prefs.getInt("handle_y", resources.displayMetrics.heightPixels / 3)
                .coerceIn(Ui.dp(this@OverlayService, 42),
                    (resources.displayMetrics.heightPixels - tabHeight - Ui.dp(this@OverlayService, 75)).coerceAtLeast(Ui.dp(this@OverlayService, 42)))
        }
        handleLp = lp

        var startX = 0f; var startY = 0f; var originalY = 0
        val threshold = Ui.dp(this, 10)
        touch.setOnClickListener { showPanel() }
        touch.setOnTouchListener { _, ev ->
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY; originalY = lp.y
                    strip.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - startY
                    if (kotlin.math.abs(dy) > threshold) {
                        lp.y = (originalY + dy).toInt().coerceIn(Ui.dp(this, 42),
                            (screenH - tabHeight - Ui.dp(this, 75)).coerceAtLeast(Ui.dp(this, 42)))
                        runCatching { wm.updateViewLayout(touch, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    strip.animate().scaleX(1.0f).scaleY(1.0f).setDuration(Ui.motionDuration(this@OverlayService,140))
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                    val dx = ev.rawX - startX; val dy = ev.rawY - startY
                    val isDrag = kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold
                    if (isDrag) {
                        prefs.edit().putInt("handle_y", lp.y).apply()
                        val shouldDockRight = ev.rawX > screenW / 2
                        if (shouldDockRight != right) {
                            right = shouldDockRight
                            prefs.edit().putBoolean("dock_right", right).apply()
                            updateHandleDock(right)
                        }
                    } else {
                        showPanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    strip.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }
        try { wm.addView(touch, lp); handle = touch }
        catch (e: Exception) { android.util.Log.e("SideNote", "Failed to add handle", e); stopSelf() }
    }

    /**
     * Compact Floating Peek Deck:
     * - Adapts strictly to content (1 note = short panel, no giant empty space)
     * - Height capped at 50% screen height with smooth internal scrolling for > 3-4 notes
     * - Sticky note visual style with pastel colors
     * - Tapping ANY note ALWAYS opens the Expanded Floating Note
     */
    /**
     * Premium Peek Deck sidebar:
     * - Draggable by the header grip — freely repositioned anywhere on screen
     * - Compact sticky-note cards with color left-accent strip, title, 1-line preview, time badge
     * - ScrollView limited to ~55% screen height (~4 visible notes) — scrolls for more
     * - Slide-in from edge + spring scale open animation
     * - Outside tap or drag-to-close dismiss
     */
    private fun showPanel() {
        if (panel != null && !closePanel()) return
        Ui.configure(this)
        val prefs = getSharedPreferences("overlay_ui", MODE_PRIVATE)
        val right = prefs.getBoolean("dock_right", false)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // ── Root card container ────────────────────────────────────────
        val root = Ui.column(this).apply {
            background = Ui.bg(Ui.card, Ui.dp(this@OverlayService, 20).toFloat(), Ui.border)
            elevation = Ui.dp(this@OverlayService, 14).toFloat()
        }

        // ── Header (drag grip + title + actions) ──────────────────────
        val headerWrap = FrameLayout(this).apply {
            val grad = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (Ui.dark) Color.rgb(28, 31, 36) else Color.rgb(248, 246, 238))
                cornerRadii = floatArrayOf(
                    Ui.dp(this@OverlayService, 20).toFloat(), Ui.dp(this@OverlayService, 20).toFloat(),
                    Ui.dp(this@OverlayService, 20).toFloat(), Ui.dp(this@OverlayService, 20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            background = grad
            setPadding(Ui.dp(this@OverlayService, 12), Ui.dp(this@OverlayService, 10),
                Ui.dp(this@OverlayService, 10), Ui.dp(this@OverlayService, 10))
        }
        val headerRow = Ui.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        // Drag grip pill
        val gripPill = View(this).apply {
            background = Ui.bg(if (Ui.dark) Color.rgb(90, 95, 105) else Color.rgb(195, 190, 180),
                Ui.dp(this@OverlayService, 3).toFloat())
            contentDescription = "Drag to move sidebar"
        }
        val gripWrap = Ui.column(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        gripWrap.addView(gripPill, LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 4)))
        headerRow.addView(gripWrap, LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)))

        // Flexible spacer (replaces 'SideNote' text title)
        val spacer = View(this)
        headerRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        // Action buttons in header
        headerRow.addView(smallIconAction(R.drawable.ic_dock_switch, "Switch dock side", Ui.ink, 16) {
            val newRight = !right
            prefs.edit().putBoolean("dock_right", newRight).apply()
            updateHandleDock(newRight)
            closePanel(); showPanel()
        })
        headerRow.addView(smallIconAction(R.drawable.ic_mic, "Voice note", Ui.ink, 16) { startVoiceOverlayNote() })
        headerRow.addView(smallIconAction(R.drawable.ic_add, "New note", Ui.ink, 16) { openEditor(store.fresh()) })
        headerRow.addView(smallIconAction(R.drawable.ic_close, "Close", Ui.ink, 16) { closePanel() })

        headerWrap.addView(headerRow, FrameLayout.LayoutParams(-1, -2))
        root.addView(headerWrap)

        // Thin accent divider under header
        root.addView(View(this).apply {
            setBackgroundColor(Ui.border); alpha = 0.6f
        }, LinearLayout.LayoutParams(-1, 1))

        // ── Notes list ────────────────────────────────────────────────
        val notes = safeNotes()?.filter { !it.archived }
            ?.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
            ?: emptyList()

        val notesScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isScrollbarFadingEnabled = true
        }
        val notesContainer = Ui.column(this).apply {
            setPadding(Ui.dp(this@OverlayService, 10), Ui.dp(this@OverlayService, 8),
                Ui.dp(this@OverlayService, 10), Ui.dp(this@OverlayService, 4))
        }
        notesScroll.addView(notesContainer)

        if (notes.isEmpty()) {
            val emptyState = Ui.column(this).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@OverlayService, 16), Ui.dp(this@OverlayService, 24),
                    Ui.dp(this@OverlayService, 16), Ui.dp(this@OverlayService, 20))
            }
            emptyState.addView(Ui.icon(this, R.drawable.ic_nav_notes, 32, Ui.muted).apply { alpha = 0.5f })
            emptyState.addView(Ui.space(this, 8))
            emptyState.addView(Ui.text(this, "No notes yet", 13f, true, Ui.ink).apply { gravity = Gravity.CENTER })
            emptyState.addView(Ui.space(this, 4))
            emptyState.addView(Ui.text(this, "Tap + to create your first note", 11f, false, Ui.muted).apply {
                gravity = Gravity.CENTER
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            })
            notesContainer.addView(emptyState)
        } else {
            notes.forEachIndexed { idx, note ->
                val colorIdx = note.color.coerceIn(0, 5)
                val noteColor = Ui.colors[colorIdx]
                val borderColor = Ui.noteBorders[colorIdx]

                // Authentic Sticky Note Card (clean rounded corners, matching border, zero strip clipping)
                val card = Ui.column(this).apply {
                    background = Ui.bg(
                        if (Ui.dark) Color.argb(190, Color.red(noteColor), Color.green(noteColor), Color.blue(noteColor))
                        else noteColor,
                        Ui.dp(this@OverlayService, 12).toFloat(), borderColor
                    )
                    elevation = Ui.dp(this@OverlayService, 2).toFloat()
                    setPadding(
                        Ui.dp(this@OverlayService, 10), Ui.dp(this@OverlayService, 8),
                        Ui.dp(this@OverlayService, 10), Ui.dp(this@OverlayService, 7)
                    )
                    isClickable = true; isFocusable = true
                    contentDescription = "Open: ${note.title.ifBlank { "Untitled" }}"
                    setOnClickListener { closePanel(); openExpandedNote(note) }
                    setOnLongClickListener { openEditor(note); true }
                    // Modern tactile press-scale feedback
                    setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(Ui.motionDuration(this@OverlayService,140))
                                    .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                            }
                        }
                        false
                    }
                    // Staggered cascade entrance animation
                    alpha = 0f
                    translationY = Ui.dp(this@OverlayService, 14).toFloat()
                    animate().alpha(1f).translationY(0f).setDuration(Ui.motionDuration(this@OverlayService,220))
                        .setStartDelay((idx * 35L).coerceAtMost(180L))
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                        .start()
                }

                // Title row with badges
                val titleRow = Ui.row(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (note.pinned) titleRow.addView(Ui.text(this, "📌 ", 10f, false, Ui.ink))
                val titleTv = Ui.text(this, note.title.ifBlank { "Untitled" }, 12.5f, true, Ui.ink).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                titleRow.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

                // Checklist badge
                if (note.isChecklist) {
                    val badge = Ui.text(this, "☑", 10f, true,
                        if (Ui.dark) Color.rgb(140, 210, 170) else Color.rgb(30, 110, 60)).apply {
                        background = Ui.bg(
                            if (Ui.dark) Color.argb(60, 30, 110, 60) else Color.argb(40, 180, 240, 200),
                            Ui.dp(this@OverlayService, 6).toFloat()
                        )
                        setPadding(Ui.dp(this@OverlayService, 4), Ui.dp(this@OverlayService, 1),
                            Ui.dp(this@OverlayService, 4), Ui.dp(this@OverlayService, 1))
                    }
                    titleRow.addView(badge, LinearLayout.LayoutParams(-2, -2).apply {
                        marginStart = Ui.dp(this@OverlayService, 4)
                    })
                }

                // Reminder badge
                if (note.reminderAt > 0L) {
                    val remBadge = Ui.text(this, "⏰", 9f, false, Ui.violet).apply {
                        background = Ui.bg(
                            if (Ui.dark) Color.argb(60, 140, 100, 255) else Color.argb(40, 120, 80, 230),
                            Ui.dp(this@OverlayService, 6).toFloat()
                        )
                        setPadding(Ui.dp(this@OverlayService, 4), Ui.dp(this@OverlayService, 1),
                            Ui.dp(this@OverlayService, 4), Ui.dp(this@OverlayService, 1))
                    }
                    titleRow.addView(remBadge, LinearLayout.LayoutParams(-2, -2).apply {
                        marginStart = Ui.dp(this@OverlayService, 4)
                    })
                }
                card.addView(titleRow)
                if(note.attachments.isNotEmpty())card.addView(Ui.text(this,NoteTemplates.badge(note),10f,true,Ui.violet))

                // Preview text (1 line max)
                val preview = RichTextFormatter.toPlainText(note.body).lineSequence().filter { it.isNotBlank() }.firstOrNull()
                    ?.removePrefix("[ ] ")?.removePrefix("[x] ")?.removePrefix("• ")
                if (!preview.isNullOrBlank()) {
                    card.addView(Ui.space(this, 2))
                    card.addView(Ui.text(this, preview.take(55), 10.5f, false,
                        if (Ui.dark) Color.rgb(185, 185, 178) else Color.rgb(90, 93, 97)).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }

                // Time badge (bottom right)
                val timeStr = try {
                    val ms = note.updatedAt
                    val now = System.currentTimeMillis()
                    val diff = now - ms
                    when {
                        diff < 60_000L -> "now"
                        diff < 3_600_000L -> "${diff / 60_000}m"
                        diff < 86_400_000L -> "${diff / 3_600_000}h"
                        diff < 604_800_000L -> "${diff / 86_400_000}d"
                        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
                    }
                } catch (_: Exception) { "" }

                if (timeStr.isNotBlank()) {
                    card.addView(Ui.space(this, 2))
                    val timeRow = Ui.row(this).apply { gravity = Gravity.END }
                    timeRow.addView(Ui.text(this, timeStr, 9f, false,
                        if (Ui.dark) Color.rgb(120, 120, 115) else Color.rgb(150, 150, 145)))
                    card.addView(timeRow)
                }

                notesContainer.addView(card, Ui.lp(this).apply {
                    topMargin = Ui.dp(this@OverlayService, 5)
                })
            }
        }

        // Exactly 4 note cards visible, rest scrollable
        val noteCardH = Ui.dp(this, 56)
        val maxVisibleNotes = 4
        val scrollH = noteCardH * maxVisibleNotes + Ui.dp(this, 20)
        root.addView(notesScroll, LinearLayout.LayoutParams(-1, scrollH))

        // ── Footer ─────────────────────────────────────────────────────
        root.addView(View(this).apply {
            setBackgroundColor(Ui.border); alpha = 0.4f
        }, LinearLayout.LayoutParams(-1, 1))

        val footerRow = Ui.row(this).apply {
            setPadding(Ui.dp(this@OverlayService, 14), Ui.dp(this@OverlayService, 8),
                Ui.dp(this@OverlayService, 10), Ui.dp(this@OverlayService, 8))
        }
        val notesLabel = "${notes.size} note${if (notes.size != 1) "s" else ""}"
        val countTv = Ui.text(this, notesLabel, 11f, false, Ui.muted)
        footerRow.addView(countTv, LinearLayout.LayoutParams(0, -2, 1f))
        val viewAll = Ui.text(this, "View all →", 11f, true, Ui.violet).apply {
            isClickable = true; isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                closePanel()
            }
        }
        footerRow.addView(viewAll)
        root.addView(footerRow)

        // ── Window params (compact 195dp width) ──────────────────────
        val panelWidth = Ui.dp(this, 195).coerceAtMost(screenW - Ui.dp(this, 24))
        val initY = prefs.getInt("panel_y",
            prefs.getInt("handle_y", screenH / 3)
        ).coerceIn(Ui.dp(this, 40), (screenH - Ui.dp(this, 200)).coerceAtLeast(Ui.dp(this, 40)))
        val initX = prefs.getInt("panel_x", Ui.dp(this, 6))

        val lp = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX; y = initY
        }

        // ── Outside-tap dismiss ───────────────────────────────────────
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false
        }

        try { wm.addView(root, lp); panel = root }
        catch (e: Exception) { android.util.Log.e("SideNote", "Peek deck error", e); return }

        // ── Drag logic on header ──────────────────────────────────────
        var dX = 0f; var dY = 0f; var dragging = false
        val dragSlop = Ui.dp(this, 6)

        val panelDragListener = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = ev.rawX - lp.x; dY = ev.rawY - lp.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (ev.rawX - dX).toInt()
                    val newY = (ev.rawY - dY).toInt()
                    if (!dragging && (kotlin.math.abs(newX - lp.x) > dragSlop || kotlin.math.abs(newY - lp.y) > dragSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = newX.coerceIn(0, (screenW - panelWidth).coerceAtLeast(0))
                        lp.y = newY.coerceIn(Ui.dp(this, 24), (screenH - Ui.dp(this, 160)).coerceAtLeast(Ui.dp(this, 24)))
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putInt("panel_x", lp.x).putInt("panel_y", lp.y).apply()
                    }
                    dragging = false; true
                }
                else -> false
            }
        }
        headerWrap.setOnTouchListener(panelDragListener)
        gripWrap.setOnTouchListener(panelDragListener)

        // ── Modern spring entry animation ─────────────────────────────
        val targetAlpha = prefs.getInt("panel_opacity", 100).coerceIn(65, 100) / 100f
        root.alpha = 0f; root.scaleX = 0.85f; root.scaleY = 0.85f
        root.translationX = if (right) Ui.dp(this, 32).toFloat() else -Ui.dp(this, 32).toFloat()
        root.animate().alpha(targetAlpha).scaleX(1f).scaleY(1f).translationX(0f)
            .setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(1.35f)).start()
    }

    private fun smallAction(symbol: String, description: String, click: () -> Unit = {}): TextView =
        Ui.text(this, symbol, 16f, true, Ui.ink).apply {
            gravity = Gravity.CENTER; minWidth = Ui.dp(this@OverlayService, 32); minHeight = Ui.dp(this@OverlayService, 32)
            isClickable = true; isFocusable = true; contentDescription = description
            setOnClickListener { click() }
        }

    private fun smallIconAction(resId: Int, description: String, tint: Int = Ui.ink, sizeDp: Int = 18, click: () -> Unit = {}): FrameLayout =
        FrameLayout(this).apply {
            minimumWidth = Ui.dp(this@OverlayService, 30); minimumHeight = Ui.dp(this@OverlayService, 30)
            isClickable = true; isFocusable = true; contentDescription = description
            val iv = Ui.icon(this@OverlayService, resId, sizeDp, tint)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            addView(iv, lp)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(60).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
                }
                false
            }
            setOnClickListener { click() }
        }

    /**
     * OPENS THE EXPANDED FLOATING NOTE:
     * - Guarantees zero duplicate windows (dismisses any previous instance synchronously)
     * - Uses authentic sticky note color with themed borders
     * - Entire header bar is smoothly draggable in 2D with magnetic snapping
     * - Body text scrolls internally for long notes
     * - Options menu for editing, color changing, opacity, size, reminders, closing
     */
    private fun openExpandedNote(note: Note, overridePosition: Pair<Int, Int>? = null) {
        if (!closePanel()) return
        Ui.configure(this)

        if (note.id !in floatingCards && floatingCards.size >= maxFloatingCards) {
            Toast.makeText(this, "Close a note before opening another", Toast.LENGTH_SHORT).show(); return
        }

        // Clean up any existing window for this note (both expanded and minimized)
        dismissFloatingCard(note.id)

        val pref = getSharedPreferences("floating_card_preferences", MODE_PRIVATE)
        val size = pref.getInt("size_${note.id}", 0).coerceIn(0, 2)
        var opacity = pref.getInt("opacity_${note.id}", 100).coerceIn(40, 100)
        var currentColorIdx = note.color.coerceIn(0, 5)

        val card = Ui.column(this).apply {
            background = Ui.bg(Ui.colors[currentColorIdx], Ui.dp(this@OverlayService, 20).toFloat(), Ui.noteBorders[currentColorIdx])
            elevation = Ui.dp(this@OverlayService, 6).toFloat()
            setPadding(Ui.dp(this@OverlayService, 12), Ui.dp(this@OverlayService, 9),
                Ui.dp(this@OverlayService, 12), Ui.dp(this@OverlayService, 10))
        }

        val header = Ui.row(this)
        val grip = smallIconAction(R.drawable.ic_drag_grip, "Drag note handle", Ui.ink, 18)
        header.addView(grip, Ui.lp(this, 30, 36))

        val title = Ui.text(this, note.title.ifBlank { "Untitled" }, 14f, true, Ui.ink).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val minBtn = smallIconAction(R.drawable.ic_minimize, "Minimize note") {
            minimizeNote(note.id)
        }
        header.addView(minBtn)

        val overflow = smallIconAction(R.drawable.ic_more, "Note options") {}
        header.addView(overflow)

        val closeBtn = smallIconAction(R.drawable.ic_close, "Close floating note") {
            dismissFloatingCard(note.id, animated = true)
        }
        header.addView(closeBtn)

        card.addView(header)

        // Overflow popup menu with sticky-note color switching, resize, opacity, edit, etc.
        overflow.setOnClickListener {
            val menuItems = listOf(
                Ui.ThemedMenuItem(label = "Files (${note.attachments.size})", iconRes = R.drawable.ic_copy) {
                    startActivity(Intent(this@OverlayService,AttachmentActivity::class.java).putExtra("id",note.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Ui.ThemedMenuItem(label = "Edit note", iconRes = R.drawable.ic_edit) {
                    editedFloatingNoteId = note.id; openEditor(note)
                },
                Ui.ThemedMenuItem(label = "Change color (${Ui.colorNames[(currentColorIdx + 1) % 6]})", iconRes = R.drawable.ic_palette) {
                    currentColorIdx = (currentColorIdx + 1) % 6
                    val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(color = currentColorIdx)
                    store.save(updated)
                    card.background = Ui.bg(Ui.colors[currentColorIdx], Ui.dp(this@OverlayService, 20).toFloat(), Ui.noteBorders[currentColorIdx])
                },
                Ui.ThemedMenuItem(label = if (note.pinned) "Unpin" else "Pin", iconRes = R.drawable.ic_pin) {
                    runCatching { store.save((store.all().firstOrNull {it.id==note.id} ?: note).copy(pinned = !note.pinned)) }
                },
                Ui.ThemedMenuItem(label = "Set reminder", iconRes = R.drawable.ic_time) {
                    val reminderItems = listOf(
                        Ui.ThemedMenuItem(label = "In 15 minutes", iconRes = R.drawable.ic_time) {
                            val rem = System.currentTimeMillis() + 15 * 60 * 1000L
                            val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = rem)
                            runCatching { store.save(updated); ReminderScheduler.schedule(this@OverlayService, updated) }
                            Toast.makeText(this@OverlayService, "Reminder set for 15 minutes", Toast.LENGTH_SHORT).show()
                        },
                        Ui.ThemedMenuItem(label = "In 1 hour", iconRes = R.drawable.ic_time) {
                            val rem = System.currentTimeMillis() + 60 * 60 * 1000L
                            val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = rem)
                            runCatching { store.save(updated); ReminderScheduler.schedule(this@OverlayService, updated) }
                            Toast.makeText(this@OverlayService, "Reminder set for 1 hour", Toast.LENGTH_SHORT).show()
                        },
                        Ui.ThemedMenuItem(label = "Tomorrow (9:00 AM)", iconRes = R.drawable.ic_time) {
                            val cal = java.util.Calendar.getInstance().apply {
                                add(java.util.Calendar.DAY_OF_YEAR, 1)
                                set(java.util.Calendar.HOUR_OF_DAY, 9)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                            }
                            val rem = cal.timeInMillis
                            val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = rem)
                            runCatching { store.save(updated); ReminderScheduler.schedule(this@OverlayService, updated) }
                            Toast.makeText(this@OverlayService, "Reminder set for tomorrow 9:00 AM", Toast.LENGTH_SHORT).show()
                        },
                        Ui.ThemedMenuItem(label = "Pick custom date & time (in app)", iconRes = R.drawable.ic_time) {
                            val intent = Intent(this@OverlayService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("pick_reminder_id", note.id)
                            }
                            startActivity(intent)
                            Toast.makeText(this@OverlayService, "Opening date & time picker...", Toast.LENGTH_SHORT).show()
                        },
                        Ui.ThemedMenuItem(label = "Clear reminder", iconRes = R.drawable.ic_close, isDestructive = true) {
                            val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = 0L)
                            runCatching { store.save(updated); ReminderScheduler.schedule(this@OverlayService, updated) }
                            Toast.makeText(this@OverlayService, "Reminder cleared", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Ui.showThemedMenu(overflow, reminderItems)
                },
                Ui.ThemedMenuItem(label = "Change opacity (${if (opacity == 100) "75%" else if (opacity == 75) "50%" else "100%"})", iconRes = R.drawable.ic_opacity) {
                    opacity = if (opacity == 100) 75 else if (opacity == 75) 50 else 100
                    pref.edit().putInt("opacity_${note.id}", opacity).apply()
                    card.alpha = opacity / 100f
                },
                Ui.ThemedMenuItem(label = "Change card size", iconRes = R.drawable.ic_resize) {
                    val nextSize = (size + 1) % 3
                    pref.edit().putInt("size_${note.id}", nextSize).apply()
                    safeNotes()?.firstOrNull { it.id == note.id }?.let { saved ->
                        openExpandedNote(saved, overridePosition = floatingCards[note.id]?.lp?.let { it.x to it.y })
                    }
                },
                Ui.ThemedMenuItem(label = "Copy text", iconRes = R.drawable.ic_copy) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SideNote", note.body))
                    Toast.makeText(this@OverlayService, "Note copied", Toast.LENGTH_SHORT).show()
                },
                Ui.ThemedMenuItem(label = "Close note", iconRes = R.drawable.ic_close, isDestructive = true) {
                    dismissFloatingCard(note.id)
                }
            )
            Ui.showThemedMenu(overflow, menuItems)
        }

        // Body area with internal scrolling so long content doesn't balloon or break the screen
        val bodyScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val bodyContent = Ui.column(this)
        bodyScroll.addView(bodyContent)

        if (note.isChecklist) {
            val lines = note.body.lines().filter { it.isNotBlank() }
            lines.take(intArrayOf(4, 7, 12)[size]).forEach { originalLine ->
                val checked = originalLine.startsWith("[x] ")
                val cb = CheckBox(this).apply {
                    text = originalLine.removePrefix("[x] ").removePrefix("[ ] ")
                    isChecked = checked
                    setTextColor(if (checked) Ui.muted else Ui.ink)
                    textSize = 12.5f
                    minimumHeight = Ui.dp(this@OverlayService, 34)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                Ui.strikeThrough(cb, checked)
                cb.setOnCheckedChangeListener { _, selected ->
                    val updated = note.body.lines().toMutableList()
                    val index = updated.indexOf(originalLine)
                    if (index >= 0) {
                        updated[index] = (if (selected) "[x] " else "[ ] ") + cb.text
                        try {
                            store.save((store.all().firstOrNull {it.id==note.id} ?: note).copy(body = updated.joinToString("\n")))
                            val currentPos = floatingCards[note.id]?.lp?.let { it.x to it.y }
                            safeNotes()?.firstOrNull { it.id == note.id }?.let { fresh ->
                                handler.post { if (!shuttingDown) openExpandedNote(fresh, overridePosition = currentPos) }
                            }
                        } catch (_: Exception) {
                            cb.setOnCheckedChangeListener(null)
                            cb.isChecked = !selected
                        }
                    }
                }
                bodyContent.addView(cb)
            }
            if (lines.size > intArrayOf(4, 7, 12)[size]) {
                bodyContent.addView(Ui.text(this, "+ ${lines.size - intArrayOf(4, 7, 12)[size]} more tasks", 11f, false, Ui.muted))
            }
        } else {
            val textBody = Ui.text(this, note.body.ifBlank { "Tap header to edit note…" }, 13f, false, Ui.ink).apply {
                maxLines = intArrayOf(5, 9, 14)[size]
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Ui.dp(this@OverlayService, 2), Ui.dp(this@OverlayService, 6),
                    Ui.dp(this@OverlayService, 2), Ui.dp(this@OverlayService, 4))
                setOnClickListener { editedFloatingNoteId = note.id; openEditor(note) }
            }
            bodyContent.addView(textBody)
        }

        val maxBodyHeight = Ui.dp(this, intArrayOf(160, 230, 310)[size])
        card.addView(bodyScroll, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@OverlayService, 4)
        })
        bodyScroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                bodyScroll.viewTreeObserver.removeOnPreDrawListener(this)
                if (bodyScroll.height > maxBodyHeight) {
                    bodyScroll.layoutParams = bodyScroll.layoutParams.apply { height = maxBodyHeight }
                }
                return true
            }
        })

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val cardWidth = Ui.dp(this, intArrayOf(240, 264, 288)[size]).coerceAtMost(screenW - Ui.dp(this, 28))

        // Saved position or smart cascade
        val savedX = pref.getInt("pos_x_${note.id}", -1)
        val savedY = pref.getInt("pos_y_${note.id}", -1)
        val defaultX = Ui.dp(this, 28) + floatingCards.size * Ui.dp(this, 16)
        val defaultY = Ui.dp(this, 110) + floatingCards.size * Ui.dp(this, 22)

        val posX = (overridePosition?.first ?: if (savedX >= 0) savedX else defaultX).coerceIn(0, (screenW - cardWidth).coerceAtLeast(0))
        val posY = (overridePosition?.second ?: if (savedY >= 0) savedY else defaultY).coerceIn(Ui.dp(this, 24), (screenH - Ui.dp(this, 240)).coerceAtLeast(Ui.dp(this, 24)))

        val lp = params(cardWidth, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            x = posX
            y = posY
        }

        // Fluid 2D header dragging
        var inCloseZone = false
        var downX = 0f; var downY = 0f; var fromX = 0; var fromY = 0
        val touchSlop = Ui.dp(this, 8)

        val dragListener = View.OnTouchListener { v, ev ->
            val currentHandleY = handleLp?.y ?: (screenH / 3)
            val dockRight = getSharedPreferences("overlay_ui", MODE_PRIVATE).getBoolean("dock_right", false)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; fromX = lp.x; fromY = lp.y; inCloseZone = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        lp.x = (fromX + dx).toInt().coerceIn(0, (screenW - cardWidth).coerceAtLeast(0))
                        val actualHeight = card.height.coerceAtLeast(Ui.dp(this, 50))
                        lp.y = (fromY + dy).toInt().coerceIn(Ui.dp(this, 24), (screenH - actualHeight - Ui.dp(this, 36)).coerceAtLeast(Ui.dp(this, 24)))
                        runCatching { wm.updateViewLayout(card, lp) }

                        // Check proximity to edge notch for drag-to-close gesture
                        val nearEdgeX = if (dockRight) (ev.rawX > screenW - Ui.dp(this, 96)) else (ev.rawX < Ui.dp(this, 96))
                        val nearHandleY = kotlin.math.abs(ev.rawY - (currentHandleY + Ui.dp(this, 44))) < Ui.dp(this, 90)

                        if (nearEdgeX && nearHandleY) {
                            if (!inCloseZone) {
                                inCloseZone = true
                                showHandleCloseState(true)
                                card.animate().alpha(0.35f).setDuration(100).start()
                            }
                        } else {
                            if (inCloseZone) {
                                inCloseZone = false
                                showHandleCloseState(false)
                                card.animate().alpha(opacity / 100f).setDuration(100).start()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(ev.rawX - downX)
                    val dy = kotlin.math.abs(ev.rawY - downY)
                    if (dx < touchSlop && dy < touchSlop) {
                        // Click on title opens note editor
                        if (v === title) {
                            editedFloatingNoteId = note.id; openEditor(note)
                        }
                    } else if (inCloseZone) {
                        inCloseZone = false
                        showHandleCloseState(false)
                        card.animate().scaleX(0.05f).scaleY(0.05f).alpha(0f).setDuration(160L).withEndAction {
                            dismissFloatingCard(note.id)
                        }.start()
                        Toast.makeText(this@OverlayService, "Note closed", Toast.LENGTH_SHORT).show()
                    } else {
                        // Magnetic edge snapping if within 26dp of either screen edge
                        val remaining = screenW - cardWidth - lp.x
                        val targetX = when {
                            lp.x < Ui.dp(this, 26) -> 0
                            remaining < Ui.dp(this, 26) -> (screenW - cardWidth).coerceAtLeast(0)
                            else -> lp.x
                        }
                        if (targetX != lp.x) {
                            android.animation.ValueAnimator.ofInt(lp.x, targetX).apply {
                                duration = 150
                                addUpdateListener { anim ->
                                    if (floatingCards[note.id]?.view === card) {
                                        lp.x = anim.animatedValue as Int
                                        runCatching { wm.updateViewLayout(card, lp) }
                                    }
                                }
                                start()
                            }
                        }
                        // Save last position
                        pref.edit().putInt("pos_x_${note.id}", lp.x).putInt("pos_y_${note.id}", lp.y).apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (inCloseZone) {
                        inCloseZone = false
                        showHandleCloseState(false)
                        card.animate().alpha(opacity / 100f).setDuration(100).start()
                    }
                    true
                }
                else -> false
            }
        }

        header.setOnTouchListener(dragListener)
        grip.setOnTouchListener(dragListener)
        title.setOnTouchListener(dragListener)

        try { wm.addView(card, lp) }
        catch (e: Exception) { android.util.Log.e("SideNote", "Error showing expanded card", e); return }

        floatingCards[note.id] = FloatingWindow(note.id, WindowMode.EXPANDED, card, lp)
        card.alpha = 0f; card.scaleX = 0.90f; card.scaleY = 0.90f
        card.animate().alpha(opacity / 100f).scaleX(1f).scaleY(1f).setDuration(200)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
    }

    /**
     * MINIMIZES AN EXPANDED NOTE INTO A COMPACT FLOATING CHIP:
     * - Suggested width: ~96dp, height ~36dp
     * - Inherits note's sticky note color
     * - Cleanly removes expanded card (NO duplicate or ghost windows behind it!)
     * - Tap restores expanded card
     */
    private fun minimizeNote(noteId: String) {
        val current = floatingCards[noteId] ?: return
        val pos = current.lp.x to current.lp.y
        val view = current.view
        // Animate the card shrinking into a pill before dismissing
        view.animate().scaleX(0.72f).scaleY(0.36f).alpha(0f).setDuration(150).withEndAction {
            dismissFloatingCard(noteId)
            safeNotes()?.firstOrNull { it.id == noteId }?.let {
                openMinimizedChip(it, overridePosition = pos)
            }
        }.start()
    }

    /**
     * OPENS COMPACT MINIMIZED CHIP (~96dp):
     * - Rounded pill capsule
     * - Draggable, snaps to edge, tap to restore
     */
    private fun openMinimizedChip(note: Note, overridePosition: Pair<Int, Int>? = null) {
        dismissFloatingCard(note.id)

        val colorIdx = note.color.coerceIn(0, 5)
        val chip = Ui.row(this).apply {
            background = Ui.bg(Ui.colors[colorIdx], Ui.dp(this@OverlayService, 18).toFloat(), Ui.noteBorders[colorIdx])
            elevation = Ui.dp(this@OverlayService, 4).toFloat()
            setPadding(Ui.dp(this@OverlayService, 8), Ui.dp(this@OverlayService, 6),
                Ui.dp(this@OverlayService, 6), Ui.dp(this@OverlayService, 6))
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Minimized note: ${note.title.ifBlank { "Untitled" }}"
        }

        val dot = View(this).apply {
            background = Ui.bg(if (Ui.dark) Color.WHITE else Color.rgb(60, 60, 60), Ui.dp(this@OverlayService, 4).toFloat())
        }
        chip.addView(dot, Ui.lp(this, 7, 7).apply { marginEnd = Ui.dp(this@OverlayService, 6); marginStart = Ui.dp(this@OverlayService, 2) })

        val titleTv = Ui.text(this, note.title.ifBlank { "Note" }, 12f, true, Ui.ink).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        chip.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

        val closeBtn = smallIconAction(R.drawable.ic_close, "Close note", Ui.ink, 14) {
            chip.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(120).withEndAction {
                dismissFloatingCard(note.id)
            }.start()
        }
        chip.addView(closeBtn, Ui.lp(this, 26, 26))

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val chipWidth = Ui.dp(this, 98).coerceAtMost(screenW - Ui.dp(this, 20))

        val posX = (overridePosition?.first ?: Ui.dp(this, 30)).coerceIn(0, (screenW - chipWidth).coerceAtLeast(0))
        val posY = (overridePosition?.second ?: Ui.dp(this, 120)).coerceIn(Ui.dp(this, 24), (screenH - Ui.dp(this, 80)).coerceAtLeast(Ui.dp(this, 24)))

        val lp = params(chipWidth, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            x = posX
            y = posY
        }

        var inCloseZone = false
        var downX = 0f; var downY = 0f; var fromX = 0; var fromY = 0
        val touchSlop = Ui.dp(this, 8)

        chip.setOnTouchListener { _, ev ->
            val currentHandleY = handleLp?.y ?: (screenH / 3)
            val dockRight = getSharedPreferences("overlay_ui", MODE_PRIVATE).getBoolean("dock_right", false)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; fromX = lp.x; fromY = lp.y; inCloseZone = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        lp.x = (fromX + dx).toInt().coerceIn(0, (screenW - chipWidth).coerceAtLeast(0))
                        lp.y = (fromY + dy).toInt().coerceIn(Ui.dp(this, 24), (screenH - Ui.dp(this, 44)).coerceAtLeast(Ui.dp(this, 24)))
                        runCatching { wm.updateViewLayout(chip, lp) }

                        val nearEdgeX = if (dockRight) (ev.rawX > screenW - Ui.dp(this, 96)) else (ev.rawX < Ui.dp(this, 96))
                        val nearHandleY = kotlin.math.abs(ev.rawY - (currentHandleY + Ui.dp(this, 44))) < Ui.dp(this, 90)

                        if (nearEdgeX && nearHandleY) {
                            if (!inCloseZone) {
                                inCloseZone = true
                                showHandleCloseState(true)
                                chip.animate().alpha(0.35f).setDuration(100).start()
                            }
                        } else {
                            if (inCloseZone) {
                                inCloseZone = false
                                showHandleCloseState(false)
                                chip.animate().alpha(1f).setDuration(100).start()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(ev.rawX - downX)
                    val dy = kotlin.math.abs(ev.rawY - downY)
                    if (dx < touchSlop && dy < touchSlop) {
                        // Tapping minimized chip RESTORES expanded note — pulse first!
                        val pos = lp.x to lp.y
                        chip.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).withEndAction {
                            dismissFloatingCard(note.id)
                            safeNotes()?.firstOrNull { it.id == note.id }?.let {
                                openExpandedNote(it, overridePosition = pos)
                            }
                        }.start()
                    } else if (inCloseZone) {
                        inCloseZone = false
                        showHandleCloseState(false)
                        chip.animate().scaleX(0.05f).scaleY(0.05f).alpha(0f).setDuration(150).withEndAction {
                            dismissFloatingCard(note.id)
                        }.start()
                        Toast.makeText(this@OverlayService, "Note closed", Toast.LENGTH_SHORT).show()
                    } else {
                        val remaining = screenW - chipWidth - lp.x
                        val targetX = when {
                            lp.x < Ui.dp(this, 26) -> 0
                            remaining < Ui.dp(this, 26) -> (screenW - chipWidth).coerceAtLeast(0)
                            else -> lp.x
                        }
                        if (targetX != lp.x) {
                            android.animation.ValueAnimator.ofInt(lp.x, targetX).apply {
                                duration = 150
                                addUpdateListener { anim ->
                                    if (floatingCards[note.id]?.view === chip) {
                                        lp.x = anim.animatedValue as Int
                                        runCatching { wm.updateViewLayout(chip, lp) }
                                    }
                                }
                                start()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (inCloseZone) {
                        inCloseZone = false
                        showHandleCloseState(false)
                        chip.animate().alpha(1f).setDuration(100).start()
                    }
                    true
                }
                else -> false
            }
        }

        try { wm.addView(chip, lp) }
        catch (e: Exception) { android.util.Log.e("SideNote", "Error showing minimized chip", e); return }

        floatingCards[note.id] = FloatingWindow(note.id, WindowMode.MINIMIZED, chip, lp)
        chip.alpha = 0f; chip.scaleX = 0.78f; chip.scaleY = 0.78f
        chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    /** Dismisses floating representation safely without leaving ghost windows. Underlying note is never deleted. */
    private fun dismissFloatingCard(noteId: String, animated: Boolean = false) {
        val w = floatingCards.remove(noteId) ?: return
        if (animated && w.view.isAttachedToWindow) {
            w.view.animate().cancel()
            w.view.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).setDuration(160)
                .withEndAction { safeRemoveView(w.view) }.start()
        } else {
            safeRemoveView(w.view)
        }
    }

    private fun dismissAllFloatingCards() {
        floatingCards.keys.toList().forEach { dismissFloatingCard(it) }
    }

    private fun startVoiceOverlayNote() {
        try {
            val intent = Intent(this, VoiceInputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start voice note: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Rich overlay note editor:
     * - Title + scrollable body EditText
     * - Formatting toolbar: Bold, Bullet, Numbered, To-do, Separator, Link
     * - 6-swatch color picker with animated active indicator
     * - Word / character count
     * - Auto-save with 450ms debounce
     * - On close: restores floating card if the note was originally floating
     */
    private fun openEditor(note: Note) {
        if (shuttingDown || !closePanel()) return
        Ui.configure(this)

        val root = Ui.column(this).apply {
            background = Ui.bg(Ui.card, Ui.dp(this@OverlayService, 22).toFloat(), Ui.border)
            elevation = Ui.dp(this@OverlayService, 16).toFloat()
            setPadding(Ui.dp(this@OverlayService, 16), Ui.dp(this@OverlayService, 12),
                Ui.dp(this@OverlayService, 16), Ui.dp(this@OverlayService, 14))
        }

        // ── State & Auto-save setup ─────────────────────────────────
        var isPinned = note.pinned
        var isArchived = note.archived
        var chosenReminderAt = note.reminderAt
        var chosenColor = note.color.coerceIn(0, 5)
        var noteFontId = NoteFontManager.getFontForNote(this, note.id)

        // ── Title & Body Views ───────────────────────────────────────
        val titleEdit = EditText(this).apply {
            hint = "Title"; setSingleLine(true); setText(note.title); textSize = 17f
            typeface = NoteFontManager.getTypeface(this@OverlayService, noteFontId, bold = true)
            setTextColor(Ui.ink); setHintTextColor(Ui.muted)
            background = Ui.bg(Ui.field, Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }

        val bodyEdit = EditText(this).apply {
            hint = if (note.isChecklist) "[ ] First task…" else "Write your note…"
            setText(RichTextFormatter.fromStorageString(note.body))
            textSize = if (noteFontId == NoteFontManager.FONT_CURSIVE) 16f else 14f; gravity = Gravity.TOP
            typeface = NoteFontManager.getTypeface(this@OverlayService, noteFontId, bold = false)
            setTextColor(Ui.ink); setHintTextColor(Ui.muted)
            background = Ui.bg(Ui.field, Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            minLines = 5
        }

        val checklistToggle = CheckBox(this).apply {
            text = "To-do"; isChecked = note.isChecklist; setTextColor(Ui.ink); textSize = 12f
        }

        val wordCount = Ui.text(this, wordCountStr(note.body), 11f, false, Ui.muted)
        val saveStatus = Ui.text(this, "  ·  Saved", 11f, false, Ui.muted)

        // ── Auto-save logic ────────────────────────────────────────────
        var dirty = false
        var pending: Runnable? = null
        val persist: () -> Boolean = {
            var saved = true
            if (dirty) {
                try {
                    val t = titleEdit.text.toString().trim()
                    val b = RichTextFormatter.toStorageString(bodyEdit.text)
                    if (t.isNotBlank() || b.isNotBlank()) {
                        store.save((store.all().firstOrNull {it.id==note.id} ?: note).copy(
                            title = t,
                            body = b,
                            isChecklist = checklistToggle.isChecked,
                            color = chosenColor,
                            pinned = isPinned,
                            archived = isArchived,
                            reminderAt = chosenReminderAt
                        ))
                    }
                    saveStatus.text = "  ·  Saved"
                    dirty = false
                } catch (e: Exception) {
                    saveStatus.text = "  ·  Save failed"
                    saved = false
                }
            }
            saved
        }
        flushDraft = { pending?.let(handler::removeCallbacks); pending = null; persist() }
        val scheduleChange = {
            dirty = true; saveStatus.text = "  ·  Saving…"
            pending?.let(handler::removeCallbacks)
            pending = Runnable { persist() }.also { handler.postDelayed(it, 450L) }
        }

        // ── Header ──────────────────────────────────────────────────
        val header = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val backBtn = smallIconAction(R.drawable.ic_arrow_back, "Back")
        header.addView(backBtn)
        header.addView(Ui.text(this, "  Edit note", 16f, true, Ui.ink), LinearLayout.LayoutParams(0, -2, 1f))

        val pinBtn = smallIconAction(R.drawable.ic_pin, "Pin note", if (isPinned) Ui.violet else Ui.ink)
        pinBtn.setOnClickListener {
            isPinned = !isPinned
            (pinBtn.getChildAt(0) as? ImageView)?.imageTintList =
                android.content.res.ColorStateList.valueOf(if (isPinned) Ui.violet else Ui.ink)
            scheduleChange()
            Toast.makeText(this, if (isPinned) "Note pinned" else "Note unpinned", Toast.LENGTH_SHORT).show()
        }
        header.addView(pinBtn)

        val archiveBtn = smallIconAction(R.drawable.ic_nav_archive, "Archive note", if (isArchived) Ui.violet else Ui.ink)
        archiveBtn.setOnClickListener {
            isArchived = !isArchived
            (archiveBtn.getChildAt(0) as? ImageView)?.imageTintList =
                android.content.res.ColorStateList.valueOf(if (isArchived) Ui.violet else Ui.ink)
            scheduleChange()
            Toast.makeText(this, if (isArchived) "Note archived" else "Note restored", Toast.LENGTH_SHORT).show()
        }
        header.addView(archiveBtn)

        val reminderBtn = smallIconAction(R.drawable.ic_time, "Reminder", if (chosenReminderAt > System.currentTimeMillis()) Ui.violet else Ui.ink)
        reminderBtn.setOnClickListener {
            val reminderItems = listOf(
                Ui.ThemedMenuItem(label = "In 15 minutes", iconRes = R.drawable.ic_time) {
                    chosenReminderAt = System.currentTimeMillis() + 15 * 60 * 1000L
                    (reminderBtn.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(Ui.violet)
                    scheduleChange()
                    ReminderScheduler.schedule(this, (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = chosenReminderAt))
                    Toast.makeText(this, "Reminder set for 15 minutes", Toast.LENGTH_SHORT).show()
                },
                Ui.ThemedMenuItem(label = "In 1 hour", iconRes = R.drawable.ic_time) {
                    chosenReminderAt = System.currentTimeMillis() + 60 * 60 * 1000L
                    (reminderBtn.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(Ui.violet)
                    scheduleChange()
                    ReminderScheduler.schedule(this, (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = chosenReminderAt))
                    Toast.makeText(this, "Reminder set for 1 hour", Toast.LENGTH_SHORT).show()
                },
                Ui.ThemedMenuItem(label = "Tomorrow (9:00 AM)", iconRes = R.drawable.ic_time) {
                    val cal = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 9)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                    }
                    chosenReminderAt = cal.timeInMillis
                    (reminderBtn.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(Ui.violet)
                    scheduleChange()
                    ReminderScheduler.schedule(this, (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = chosenReminderAt))
                    Toast.makeText(this, "Reminder set for tomorrow 9:00 AM", Toast.LENGTH_SHORT).show()
                },
                Ui.ThemedMenuItem(label = "Pick custom date & time (in app)", iconRes = R.drawable.ic_time) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("pick_reminder_id", note.id)
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Opening date & time picker...", Toast.LENGTH_SHORT).show()
                },
                Ui.ThemedMenuItem(label = "Clear reminder", iconRes = R.drawable.ic_close, isDestructive = true) {
                    chosenReminderAt = 0L
                    (reminderBtn.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(Ui.ink)
                    scheduleChange()
                    ReminderScheduler.schedule(this, (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = 0L))
                    Toast.makeText(this, "Reminder cleared", Toast.LENGTH_SHORT).show()
                }
            )
            Ui.showThemedMenu(reminderBtn, reminderItems)
        }
        header.addView(reminderBtn)

        val doneBtn = smallIconAction(R.drawable.ic_check, "Done", Ui.violet)
        header.addView(doneBtn)
        root.addView(header)
        root.addView(Ui.space(this, 8))

        root.addView(titleEdit)
        root.addView(Ui.space(this, 8))

        val bodyScroll = ScrollView(this).apply { isFillViewport = true; addView(bodyEdit) }
        root.addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Ui.space(this, 6))

        // ── Formatting toolbar ────────────────────────────────────────
        val toolbar = Ui.row(this)
        fun fmtIconBtn(iconRes: Int, desc: String, action: () -> Unit): View =
            FrameLayout(this).apply {
                minimumWidth = Ui.dp(this@OverlayService, 36); minimumHeight = Ui.dp(this@OverlayService, 32)
                background = Ui.bg(Ui.field, Ui.dp(this@OverlayService, 8).toFloat())
                isClickable = true; isFocusable = true; contentDescription = desc
                val iv = Ui.icon(this@OverlayService, iconRes, 18, Ui.ink)
                val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                addView(iv, lp)
                setOnClickListener { action() }
            }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(this@OverlayService, 4) }

        var undoBtn: View? = null
        var redoBtn: View? = null
        val historyManager = TextHistoryManager { canUndo, canRedo ->
            undoBtn?.alpha = if (canUndo) 1.0f else 0.4f
            undoBtn?.isClickable = canUndo
            redoBtn?.alpha = if (canRedo) 1.0f else 0.4f
            redoBtn?.isClickable = canRedo
        }
        historyManager.init(note.body)

        undoBtn = fmtIconBtn(R.drawable.ic_undo, "Undo") {
            val prev = historyManager.undo(bodyEdit.text.toString())
            if (prev != null) {
                bodyEdit.setText(prev)
                bodyEdit.setSelection(prev.length.coerceAtMost(bodyEdit.text.length))
                scheduleChange()
            }
        }.also {
            it.alpha = 0.4f
            it.isClickable = false
            toolbar.addView(it, gap)
        }

        redoBtn = fmtIconBtn(R.drawable.ic_redo, "Redo") {
            val next = historyManager.redo(bodyEdit.text.toString())
            if (next != null) {
                bodyEdit.setText(next)
                bodyEdit.setSelection(next.length.coerceAtMost(bodyEdit.text.length))
                scheduleChange()
            }
        }.also {
            it.alpha = 0.4f
            it.isClickable = false
            toolbar.addView(it, gap)
        }

        toolbar.addView(fmtIconBtn(R.drawable.ic_format_bold, "Bold") {
            RichTextFormatter.applyBold(bodyEdit)
            scheduleChange()
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_format_italic, "Italic") {
            RichTextFormatter.applyItalic(bodyEdit)
            scheduleChange()
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_format_header, "Header") {
            RichTextFormatter.applyHeading(bodyEdit)
            scheduleChange()
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_bullet_list, "Bullet") {
            val pos = bodyEdit.selectionStart.coerceAtLeast(0)
            val lineStart = bodyEdit.text.toString().lastIndexOf('\n', pos - 1) + 1
            bodyEdit.text.insert(lineStart, "• ")
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_checkbox, "To-do") {
            val pos = bodyEdit.selectionStart.coerceAtLeast(0)
            val lineStart = bodyEdit.text.toString().lastIndexOf('\n', pos - 1) + 1
            bodyEdit.text.insert(lineStart, "[ ] ")
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_minimize, "Separator") {
            bodyEdit.text.insert(bodyEdit.selectionStart.coerceAtLeast(0), "\n---\n")
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_link, "Link") {
            val s = bodyEdit.selectionStart.coerceAtLeast(0)
            val e = bodyEdit.selectionEnd.coerceAtLeast(s)
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clip?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            val url = if (clipText.startsWith("http://") || clipText.startsWith("https://")) clipText else "https://"
            if (s < e) {
                val sel = bodyEdit.text.substring(s, e)
                bodyEdit.text.replace(s, e, "[$sel]($url)")
                bodyEdit.setSelection(s + sel.length + url.length + 4)
            } else {
                bodyEdit.text.insert(s, url)
                bodyEdit.setSelection(s + url.length)
            }
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_mic, "Voice dictation") {
            try {
                val intent = Intent(this, VoiceInputActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(VoiceInputActivity.EXTRA_TARGET_NOTE_ID, note.id)
                    putExtra(VoiceInputActivity.EXTRA_APPEND_MODE, true)
                    putExtra(VoiceInputActivity.EXTRA_EDITOR_ACTIVE, true)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start voice dictation", Toast.LENGTH_SHORT).show()
            }
        }, gap)
        toolbar.addView(fmtIconBtn(R.drawable.ic_font, "Font style") {
            val currFont = NoteFontManager.getFontForNote(this, note.id)
            NoteFontManager.showFontPickerDialog(this, currFont) { chosenFont ->
                NoteFontManager.setFontForNote(this, note.id, chosenFont)
                noteFontId = chosenFont
                titleEdit.typeface = NoteFontManager.getTypeface(this@OverlayService, chosenFont, bold = true)
                bodyEdit.typeface = NoteFontManager.getTypeface(this@OverlayService, chosenFont, bold = false)
                bodyEdit.textSize = if (chosenFont == NoteFontManager.FONT_CURSIVE) 16f else 14f
                dirty = true
                scheduleChange()
                val item = NoteFontManager.FONTS.find { it.id == chosenFont }
                Toast.makeText(this, "Font: ${item?.displayName ?: chosenFont}", Toast.LENGTH_SHORT).show()
            }
        }, gap)
        root.addView(toolbar)
        root.addView(Ui.space(this, 8))

        // ── Color picker row ──────────────────────────────────────────
        val colorRow = Ui.row(this)
        val colorSwatches = mutableListOf<View>()
        Ui.colors.forEachIndexed { idx, color ->
            val swatch = View(this).apply {
                background = Ui.bg(color, Ui.dp(this@OverlayService, 12).toFloat(), Ui.noteBorders[idx])
                contentDescription = Ui.colorNames[idx]
                isClickable = true; isFocusable = true
                scaleX = if (idx == chosenColor) 1.25f else 1f
                scaleY = if (idx == chosenColor) 1.25f else 1f
                setOnClickListener {
                    chosenColor = idx
                    scheduleChange()
                    colorSwatches.forEachIndexed { i, v ->
                        v.animate().scaleX(if (i == idx) 1.25f else 1f).scaleY(if (i == idx) 1.25f else 1f).setDuration(120).start()
                    }
                }
            }
            colorSwatches.add(swatch)
            colorRow.addView(swatch, LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)).apply {
                marginEnd = Ui.dp(this@OverlayService, 6)
            })
        }
        root.addView(colorRow)
        root.addView(Ui.space(this, 8))

        // ── Footer: checklist toggle + word count + save status ───────
        val footer = Ui.row(this)
        footer.addView(checklistToggle, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(wordCount)
        footer.addView(saveStatus)
        root.addView(footer)

        // ── Window params ──────────────────────────────────────────────
        val width = Ui.dp(this, 304).coerceAtMost(resources.displayMetrics.widthPixels - Ui.dp(this, 24))
        val height = Ui.dp(this, 370).coerceAtMost((resources.displayMetrics.heightPixels * 0.60f).toInt())
        val p = params(width, height, Gravity.TOP or Gravity.CENTER_HORIZONTAL, focus = true).apply {
            y = Ui.dp(this@OverlayService, 60)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { wm.addView(root, p); panel = root }
        catch (e: Exception) {
            android.util.Log.e("SideNote", "Unable to display notes panel", e)
            Toast.makeText(this, "Could not open notes panel", Toast.LENGTH_LONG).show()
            return
        }
        val targetAlpha = getSharedPreferences("overlay_ui", MODE_PRIVATE).getInt("panel_opacity", 100).coerceIn(65, 100) / 100f
        root.alpha = 0f; root.translationY = Ui.dp(this, 14).toFloat()
        root.animate().alpha(targetAlpha).translationY(0f).setDuration(190)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentText = bodyEdit.text.toString()
                wordCount.text = wordCountStr(currentText)
                historyManager.onTextChanged(currentText)
                scheduleChange()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        titleEdit.addTextChangedListener(watcher)
        bodyEdit.addTextChangedListener(watcher)

        // Enter key in checklist mode auto-prefixes [ ]
        bodyEdit.setOnKeyListener { _, keyCode, event ->
            if (checklistToggle.isChecked && event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                val s = bodyEdit.selectionStart.coerceAtLeast(0)
                val e = bodyEdit.selectionEnd.coerceAtLeast(s)
                bodyEdit.text.replace(s, e, "\n[ ] ")
                bodyEdit.setSelection(s + 5)
                scheduleChange()
                true
            } else false
        }

        checklistToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val lines = bodyEdit.text.toString().lines().map { line ->
                    if (line.isNotBlank() && !line.startsWith("[ ] ") && !line.startsWith("[x] ")) "[ ] $line" else line
                }
                bodyEdit.setText(lines.joinToString("\n"))
            }
            scheduleChange()
        }

        // Live Voice Receiver for active overlay editor
        val voiceReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val text = intent?.getStringExtra(VoiceInputActivity.EXTRA_RESULT_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    val s = bodyEdit.selectionStart.coerceAtLeast(0)
                    val e = bodyEdit.selectionEnd.coerceAtLeast(s)
                    val space = if (bodyEdit.text.isNotEmpty() && !bodyEdit.text.endsWith(" ") && !bodyEdit.text.endsWith("\n")) " " else ""
                    bodyEdit.text.replace(s, e, "$space$text")
                    bodyEdit.setSelection((s + space.length + text.length).coerceAtMost(bodyEdit.text.length))
                    scheduleChange()
                }
            }
        }
        overlayEditorVoiceReceiver = voiceReceiver
        val voiceFilter = android.content.IntentFilter(VoiceInputActivity.ACTION_VOICE_RESULT)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceReceiver, voiceFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(voiceReceiver, voiceFilter)
        }

        val closeEditor: () -> Unit = {
            val fromCard = editedFloatingNoteId != null
            if (closePanel() && !fromCard) showPanel()
        }
        backBtn.setOnClickListener { closeEditor() }
        doneBtn.setOnClickListener { closeEditor() }
        root.isFocusableInTouchMode = true; root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                closeEditor(); true
            } else false
        }
    }

    private fun wordCountStr(text: String): String {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return "${words}w · ${text.length}c"
    }

    private fun closePanel(animated: Boolean = true): Boolean {
        overlayEditorVoiceReceiver?.let {
            runCatching { unregisterReceiver(it) }
            overlayEditorVoiceReceiver = null
        }
        if (flushDraft?.invoke() == false) return false
        flushDraft = null
        val p = panel
        panel = null
        if (p != null) {
            if (animated && p.isAttachedToWindow) {
                val prefs = getSharedPreferences("overlay_ui", MODE_PRIVATE)
                val right = prefs.getBoolean("dock_right", false)
                val exitX = if (right) Ui.dp(this, 36).toFloat() else -Ui.dp(this, 36).toFloat()
                p.animate().cancel()
                p.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).translationX(exitX)
                    .setDuration(160)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { safeRemoveView(p) }
                    .start()
            } else {
                safeRemoveView(p)
            }
        }
        val editedId = editedFloatingNoteId
        editedFloatingNoteId = null
        if (!shuttingDown && editedId != null && editedId in floatingCards) {
            val current = floatingCards[editedId]
            val savedNote = safeNotes()?.firstOrNull { it.id == editedId }
            if (current != null && savedNote != null) {
                handler.post {
                    if (!shuttingDown && panel == null && ::wm.isInitialized && Settings.canDrawOverlays(this)) {
                        if (current.mode == WindowMode.MINIMIZED) openMinimizedChip(savedNote, current.lp.x to current.lp.y)
                        else openExpandedNote(savedNote, current.lp.x to current.lp.y)
                    }
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        shuttingDown = true
        runCatching {unregisterReceiver(syncUpdates)}
        handler.removeCallbacksAndMessages(null)
        if (!closePanel(animated = false)) android.util.Log.e("SideNote", "Draft save failed during shutdown")
        dismissAllFloatingCards()
        safeRemoveView(handle)
        handle = null
        handleStrip = null
        handleImage = null
        handleCloseBadge = null
        super.onDestroy()
    }
}
