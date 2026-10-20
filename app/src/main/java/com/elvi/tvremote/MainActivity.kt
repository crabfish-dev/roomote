package com.elvi.tvremote

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.elvi.tvremote.ui.DPad
import com.elvi.tvremote.ui.IconButton
import com.elvi.tvremote.ui.RoomoteStyle
import com.elvi.tvremote.ui.Symbol
import com.elvi.tvremote.ui.SymbolView
import com.elvi.tvremote.ui.dp
import com.elvi.tvremote.ui.horizontal
import com.elvi.tvremote.ui.ripple
import com.elvi.tvremote.ui.rounded
import com.elvi.tvremote.ui.text
import com.elvi.tvremote.ui.vertical

class MainActivity : Activity() {
    private lateinit var controller: RemoteController
    private var state = RemoteUiState()
    private val controls = mutableListOf<View>()
    private lateinit var headerSubtitle: TextView
    private lateinit var settingsButton: IconButton
    private lateinit var connectionDot: View
    private var connectionTitle: TextView? = null
    private var connectionSubtitle: TextView? = null
    private var connectionBadge: TextView? = null
    private var statusText: TextView? = null
    private var statusActions: LinearLayout? = null
    private lateinit var volumeText: TextView
    private lateinit var muteButton: IconButton
    private var pickerDialog: Dialog? = null
    private var pickerDevices: LinearLayout? = null
    private var pickerStatus: TextView? = null
    private var pairDialog: Dialog? = null
    private var pairError: TextView? = null
    private var pairSubmit: TextView? = null
    private var pairInput: EditText? = null
    private var pairingDraft = ""
    private var skipFirstForeground = false
    private val openDialogs = mutableSetOf<Dialog>()
    private var lastPhase = ConnectionPhase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingDraft = savedInstanceState?.getString("pairingDraft").orEmpty()
        buildScreen()
        configureWindow()
        window.decorView.requestApplyInsets()
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RemoteController
        skipFirstForeground = retained != null
        controller = retained ?: RemoteController(applicationContext, ::render)
        controller.attach(::render)
        if (retained == null) controller.start()
    }

    override fun onStart() {
        super.onStart()
        // Rotation is the same visit: retain pairing and explicit disconnect decisions.
        if (skipFirstForeground) skipFirstForeground = false else controller.onForeground()
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = controller

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pairingDraft", pairInput?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        controller.detach()
        openDialogs.toList().forEach { dialog ->
            dialog.setOnCancelListener(null)
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        openDialogs.clear()
        if (!isChangingConfigurations) controller.close()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 27) RoomoteStyle.background else RoomoteStyle.ink
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            val lightNavigation = if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or lightNavigation
        }
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(RoomoteStyle.background)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = true
        }
        val compactScreen = resources.configuration.screenHeightDp < 800
        val page = vertical(this).apply { setPadding(dp(24), dp(12), dp(24), dp(24)) }
        scroll.addView(page, LinearLayout.LayoutParams(-1, -2))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)

        val header = horizontal(this)
        val name = vertical(this)
        name.addView(text(this, "Roomote", 34f, bold = true).apply { letterSpacing = -0.045f })
        headerSubtitle = text(this, "Tap the TV icon to connect", 10f, RoomoteStyle.muted).apply {
            letterSpacing = 0.03f
            setPadding(0, dp(7), 0, 0)
        }
        name.addView(headerSubtitle)
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        settingsButton = IconButton(this, Symbol.TV, "TV settings. Not connected.", RoomoteStyle.accentLight, RoomoteStyle.accent, 18f, 26)
        settingsButton.setOnClickListener { showPicker() }
        connectionDot = View(this).apply {
            background = rounded(RoomoteStyle.green, 5f, context = this@MainActivity)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        settingsButton.addView(connectionDot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        })
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(54), dp(54)))
        page.addView(header)
        page.space(if (compactScreen) 24 else 32)

        val topActions = horizontal(this).apply { gravity = Gravity.CENTER }
        topActions.addView(labeledControl(Symbol.POWER, "Power", KeyEvent.KEYCODE_POWER, RoomoteStyle.accentLight, RoomoteStyle.accent), LinearLayout.LayoutParams(0, -2, 1f))
        topActions.addView(text(this, "NAVIGATION", 10f, RoomoteStyle.muted, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }, LinearLayout.LayoutParams(0, -2, 1.35f))
        topActions.addView(labeledControl(Symbol.INPUT, "Input", KeyEvent.KEYCODE_TV_INPUT), LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(topActions, LinearLayout.LayoutParams(-1, -2))
        page.space(if (compactScreen) 16 else 20)
        val dpad = DPad(this, ::sendKey)
        controls += dpad.keyButtons
        val dpadSize = if (compactScreen) 224 else 240
        page.addView(dpad, LinearLayout.LayoutParams(dp(dpadSize), dp(dpadSize)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        page.space(if (compactScreen) 16 else 20)
        val navigation = horizontal(this)
        navigation.addView(labeledControl(Symbol.BACK, "Back", KeyEvent.KEYCODE_BACK), LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(labeledControl(Symbol.HOME, "Home", KeyEvent.KEYCODE_HOME), LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(labeledControl(Symbol.MENU, "Menu", KeyEvent.KEYCODE_MENU), LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(navigation)
        page.space(if (compactScreen) 20 else 24)

        val cards = horizontal(this).apply { gravity = Gravity.TOP }
        val sound = vertical(this).apply {
            background = rounded(RoomoteStyle.surface, 24f, context = this@MainActivity)
            setPadding(dp(10), dp(15), dp(10), dp(10))
        }
        volumeText = text(this, "VOLUME", 9f, RoomoteStyle.muted, true).apply {
            letterSpacing = 0.12f; gravity = Gravity.CENTER
        }
        sound.addView(volumeText, LinearLayout.LayoutParams(-1, -2))
        sound.space(9)
        val volumeRow = horizontal(this).apply { gravity = Gravity.CENTER }
        volumeRow.addView(keyControl(Symbol.MINUS, "Volume down", KeyEvent.KEYCODE_VOLUME_DOWN, Color.TRANSPARENT), LinearLayout.LayoutParams(0, dp(50), 1f))
        volumeRow.addView(keyControl(Symbol.PLUS, "Volume up", KeyEvent.KEYCODE_VOLUME_UP, Color.TRANSPARENT), LinearLayout.LayoutParams(0, dp(50), 1f))
        sound.addView(volumeRow)
        muteButton = keyControl(Symbol.MUTE, "Mute or unmute", KeyEvent.KEYCODE_VOLUME_MUTE, RoomoteStyle.white)
        sound.addView(muteButton, LinearLayout.LayoutParams(-1, dp(48)))
        cards.addView(sound, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })

        val playback = vertical(this).apply {
            background = rounded(RoomoteStyle.surface, 24f, context = this@MainActivity)
            setPadding(dp(10), dp(15), dp(10), dp(10))
        }
        playback.addView(text(this, "PLAYBACK", 9f, RoomoteStyle.muted, true).apply {
            letterSpacing = 0.12f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        playback.space(9)
        val tracks = horizontal(this)
        tracks.addView(keyControl(Symbol.PREVIOUS, "Previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, Color.TRANSPARENT), LinearLayout.LayoutParams(0, dp(50), 1f))
        tracks.addView(keyControl(Symbol.NEXT, "Next", KeyEvent.KEYCODE_MEDIA_NEXT, Color.TRANSPARENT), LinearLayout.LayoutParams(0, dp(50), 1f))
        playback.addView(tracks)
        playback.addView(keyControl(Symbol.PLAY_PAUSE, "Play or pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RoomoteStyle.ink, RoomoteStyle.white), LinearLayout.LayoutParams(-1, dp(48)))
        cards.addView(playback, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(cards, LinearLayout.LayoutParams(-1, -2))
    }

    private fun labeledControl(icon: Symbol, label: String, key: Int, background: Int = RoomoteStyle.surface, tint: Int = RoomoteStyle.ink): LinearLayout {
        val column = vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(keyControl(icon, label, key, background, tint), LinearLayout.LayoutParams(dp(58), dp(50)))
        column.addView(text(this, label, 11f, RoomoteStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return column
    }

    private fun keyControl(icon: Symbol, label: String, key: Int, color: Int = RoomoteStyle.surface, tint: Int = RoomoteStyle.ink) =
        IconButton(this, icon, label, color, tint, 17f).apply {
            controls += this
            setOnClickListener { sendKey(key) }
        }

    private fun sendKey(code: Int) {
        if (state.phase == ConnectionPhase.CONNECTED) {
            window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            controller.sendKey(code)
        }
    }

    private fun render(newState: RemoteUiState) {
        if (isFinishing || isDestroyed) return
        val oldPhase = lastPhase
        state = newState
        lastPhase = state.phase
        val connected = state.phase == ConnectionPhase.CONNECTED
        controls.forEach {
            it.isEnabled = connected
            it.alpha = if (connected) 1f else 0.38f
        }
        headerSubtitle.text = when (state.phase) {
            ConnectionPhase.CONNECTED -> "YOUR TV, WITHIN REACH"
            ConnectionPhase.CONNECTING -> "Connecting to your TV…"
            ConnectionPhase.PAIRING, ConnectionPhase.AWAITING_CODE -> "Check your TV for the pairing code"
            ConnectionPhase.ERROR -> if (state.automaticConnection) "Couldn't reconnect. Tap the TV icon" else "Connection needs attention"
            else -> "Tap the TV icon to connect"
        }
        headerSubtitle.setTextColor(if (state.phase == ConnectionPhase.ERROR) RoomoteStyle.accent else RoomoteStyle.muted)
        headerSubtitle.letterSpacing = if (connected) 0.16f else 0.03f
        settingsButton.contentDescription = buildString {
            append("TV settings. ")
            state.selected?.let { append("${it.name}. ") }
            append(connectionLabel().lowercase())
            if (state.phase == ConnectionPhase.ERROR) append(". ${state.message}")
        }
        connectionDot.visibility = if (connected) View.VISIBLE else View.GONE
        settingsButton.symbolView.tint = if (connected) RoomoteStyle.green else RoomoteStyle.accent
        settingsButton.symbolView.invalidate()
        volumeText.text = if (state.volume != null && state.maxVolume != null && state.maxVolume!! > 0) {
            "VOLUME  ${(state.volume!! * 100 / state.maxVolume!!).coerceIn(0, 100)}%"
        } else "VOLUME"
        muteButton.isSelected = state.muted
        muteButton.symbolView.tint = if (state.muted) RoomoteStyle.accent else RoomoteStyle.ink
        muteButton.symbolView.invalidate()
        refreshPicker()
        if (state.phase == ConnectionPhase.AWAITING_CODE && pairDialog == null) showPairing()
        if (state.phase == ConnectionPhase.CONNECTED || state.phase == ConnectionPhase.IDLE) {
            pairingDraft = ""
            pairDialog?.setOnCancelListener(null)
            pairDialog?.dismiss()
            pairDialog = null
            if (state.phase == ConnectionPhase.CONNECTED && oldPhase != ConnectionPhase.CONNECTED) pickerDialog?.dismiss()
        } else if (pairDialog != null) {
            pairError?.text = if (state.phase == ConnectionPhase.ERROR) state.message else ""
            pairSubmit?.isEnabled = state.phase == ConnectionPhase.AWAITING_CODE || state.phase == ConnectionPhase.ERROR
            pairSubmit?.text = when (state.phase) {
                ConnectionPhase.PAIRING, ConnectionPhase.CONNECTING -> "Checking code…"
                ConnectionPhase.ERROR -> "Request a new code"
                else -> "Connect TV"
            }
        }
        if (state.phase == ConnectionPhase.ERROR && !state.automaticConnection && oldPhase != ConnectionPhase.ERROR &&
            pairDialog == null && pickerDialog == null) showPicker(scan = false)
    }

    private fun connectionLabel() = when (state.phase) {
        ConnectionPhase.IDLE -> "NOT CONNECTED"
        ConnectionPhase.CONNECTING -> "CONNECTING"
        ConnectionPhase.CONNECTED -> "CONNECTED"
        ConnectionPhase.PAIRING -> "STARTING PAIRING"
        ConnectionPhase.AWAITING_CODE -> "CHECK YOUR TV"
        ConnectionPhase.ERROR -> "CONNECTION NEEDS ATTENTION"
    }

    private fun rebuildStatusActions() {
        val actions = statusActions ?: return
        actions.removeAllViews()
        when (state.phase) {
            ConnectionPhase.ERROR -> {
                state.selected?.let { selected ->
                    actions.addView(smallAction("Try again") { controller.select(selected) })
                    actions.addView(smallAction("Pair TV") { controller.pair() })
                }
            }
            ConnectionPhase.CONNECTED -> actions.addView(smallAction("Disconnect") { controller.disconnect() })
            ConnectionPhase.PAIRING, ConnectionPhase.AWAITING_CODE -> actions.addView(smallAction("Cancel pairing") { controller.cancelPairing() })
            ConnectionPhase.CONNECTING -> actions.addView(smallAction("Cancel") { controller.disconnect() })
            ConnectionPhase.IDLE -> state.selected?.let { selected ->
                actions.addView(smallAction("Connect TV") { controller.select(selected) })
            }
        }
        if (state.selected?.saved == true && state.phase in listOf(ConnectionPhase.IDLE, ConnectionPhase.CONNECTED, ConnectionPhase.ERROR)) {
            actions.addView(smallAction("Forget TV") { controller.forgetSelected() })
        }
        actions.visibility = if (actions.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun smallAction(label: String, action: () -> Unit) = text(this, label, 12f, RoomoteStyle.accent, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        isFocusable = true
        background = ripple(this@MainActivity, Color.TRANSPARENT, 14f)
        setOnClickListener { action() }
        asAccessibleButton()
    }

    private fun showPicker(scan: Boolean = true) {
        if (pickerDialog != null) return
        val (dialog, body) = createDialog("TV settings", "Connect and manage your Android TV or Google TV.")
        pickerDialog = dialog
        val connectionCard = vertical(this).apply {
            background = rounded(RoomoteStyle.white, 20f, RoomoteStyle.border, this@MainActivity)
            setPadding(dp(16), dp(15), dp(16), dp(13))
        }
        connectionTitle = text(this, "", 17f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        connectionSubtitle = text(this, "", 12f, RoomoteStyle.muted).apply {
            setPadding(0, dp(6), 0, 0)
            maxLines = 2
        }
        connectionBadge = text(this, "", 9f, RoomoteStyle.muted, true).apply {
            letterSpacing = 0.09f
            setPadding(0, dp(12), 0, 0)
        }
        connectionCard.addView(connectionTitle)
        connectionCard.addView(connectionSubtitle)
        connectionCard.addView(connectionBadge)
        body.addView(connectionCard, LinearLayout.LayoutParams(-1, -2))
        statusText = text(this, "", 12f, RoomoteStyle.muted).apply {
            setPadding(0, dp(12), 0, dp(4))
            setLineSpacing(dp(3).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(statusText)
        statusActions = vertical(this).apply { gravity = Gravity.CENTER }
        body.addView(statusActions, LinearLayout.LayoutParams(-1, -2))
        body.space(16)
        body.addView(text(this, "YOUR TELEVISIONS", 10f, RoomoteStyle.muted, true).apply { letterSpacing = 0.12f })
        pickerStatus = text(this, "", 12f, RoomoteStyle.muted).apply { setPadding(0, dp(5), 0, dp(14)) }
        body.addView(pickerStatus)
        val devicesScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        pickerDevices = vertical(this)
        devicesScroll.addView(pickerDevices)
        body.addView(devicesScroll, LinearLayout.LayoutParams(-1, dp(160)))
        body.addView(smallAction("Scan again") { controller.rescan() }, LinearLayout.LayoutParams(-1, -2))
        body.space(10)
        body.addView(text(this, "CONNECT BY ADDRESS", 10f, RoomoteStyle.muted, true).apply { letterSpacing = 0.12f })
        body.space(10)
        val address = editText("TV IP address or hostname").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        address.minimumHeight = dp(54)
        body.addView(address, LinearLayout.LayoutParams(-1, -2))
        body.space(10)
        val manualError = text(this, "", 12f, RoomoteStyle.accent).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, dp(10))
        }
        body.addView(manualError)
        val submit: () -> Unit = {
            val host = address.text.toString().trim()
            if (host.isEmpty()) {
                manualError.text = "Enter the address shown in your TV’s network settings."
                manualError.visibility = View.VISIBLE
                address.requestFocus()
            } else {
                val accepted = controller.connectManual(host)
                if (accepted) {
                    hideKeyboard(address)
                    dialog.dismiss()
                } else {
                    manualError.text = state.message
                    manualError.visibility = View.VISIBLE
                }
            }
        }
        body.addView(primaryAction("Connect by address", submit), LinearLayout.LayoutParams(-1, -2))
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { submit(); true } else false
        }
        body.space(8)
        body.addView(smallAction("Connection help") { showHelp() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(smallAction("Privacy & support") { showPrivacy() }, LinearLayout.LayoutParams(-1, -2))
        dialog.setOnDismissListener {
            openDialogs.remove(dialog)
            pickerDialog = null
            pickerDevices = null
            pickerStatus = null
            connectionTitle = null
            connectionSubtitle = null
            connectionBadge = null
            statusText = null
            statusActions = null
        }
        refreshPicker()
        dialog.showStyled()
        if (scan && !state.scanning && state.phase == ConnectionPhase.IDLE) controller.rescan()
    }

    private fun refreshPicker() {
        val list = pickerDevices ?: return
        val connected = state.phase == ConnectionPhase.CONNECTED
        connectionTitle?.text = state.selected?.name ?: "Choose your TV"
        connectionSubtitle?.text = state.selected?.host ?: "Keep your phone and TV on the same Wi-Fi."
        connectionBadge?.text = connectionLabel()
        connectionBadge?.setTextColor(if (connected) RoomoteStyle.green else if (state.phase == ConnectionPhase.ERROR) RoomoteStyle.accent else RoomoteStyle.muted)
        statusText?.text = state.message.ifBlank {
            if (connected) "Connected over your local network." else "Choose a TV below or enter its address."
        }
        statusText?.setTextColor(if (state.phase == ConnectionPhase.ERROR) RoomoteStyle.accent else RoomoteStyle.muted)
        rebuildStatusActions()
        pickerStatus?.text = when {
            state.scanning -> "Searching your local network…"
            state.devices.isEmpty() -> "No TVs found yet. Scan again or connect by address."
            else -> "Tap a TV to connect. New TVs will show a pairing code."
        }
        list.removeAllViews()
        if (state.devices.isEmpty()) {
            val empty = vertical(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(18), dp(10), dp(18))
                background = rounded(RoomoteStyle.surface, 18f, context = this@MainActivity)
            }
            empty.addView(SymbolView(this, Symbol.WIFI, RoomoteStyle.muted), LinearLayout.LayoutParams(dp(32), dp(32)))
            empty.space(12)
            empty.addView(text(this, if (state.scanning) "Finding your next watch" else "Your TV will appear here", 14f, RoomoteStyle.muted).apply { gravity = Gravity.CENTER })
            empty.space(8)
            empty.addView(text(this, "Keep the TV switched on and connected.", 12f, RoomoteStyle.muted).apply { gravity = Gravity.CENTER })
            list.addView(empty, LinearLayout.LayoutParams(-1, dp(160)))
        }
        state.devices.sortedWith(compareByDescending<TvDevice> { it.saved }.thenBy { it.name }).forEach { tv ->
            val row = horizontal(this).apply {
                setPadding(dp(13), dp(14), dp(13), dp(14))
                background = ripple(this@MainActivity, RoomoteStyle.surface, 18f)
                isClickable = true
                isFocusable = true
                contentDescription = "${tv.name}, ${tv.host}${if (tv.saved) ", paired TV" else ""}"
                setOnClickListener { pickerDialog?.dismiss(); controller.select(tv) }
            }
            row.addView(SymbolView(this, Symbol.TV, RoomoteStyle.accent), LinearLayout.LayoutParams(dp(25), dp(25)).apply { marginEnd = dp(12) })
            val names = vertical(this)
            names.addView(text(this, tv.name, 14f, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            names.addView(text(this, "${tv.host}${if (tv.saved) " · Paired" else ""}", 11f, RoomoteStyle.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(SymbolView(this, Symbol.CHEVRON_RIGHT, RoomoteStyle.muted), LinearLayout.LayoutParams(dp(16), dp(16)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showPairing() {
        val (dialog, body) = createDialog("A little introduction", "Enter the 6-character pairing code shown on ${state.selected?.name ?: "your TV"}.")
        pairDialog = dialog
        val code = editText("A1B2C3").apply {
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.22f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6), InputFilter { source, start, end, _, _, _ ->
                val filtered = source.subSequence(start, end).filter { it.isDigit() && it <= '9' || it.uppercaseChar() in 'A'..'F' }
                if (filtered.length == end - start) null else filtered
            })
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            contentDescription = "Six-character pairing code from your TV"
            minimumHeight = dp(66)
            setText(pairingDraft)
            setSelection(text.length)
        }
        pairingDraft = ""
        pairInput = code
        body.space(12)
        body.addView(code, LinearLayout.LayoutParams(-1, -2))
        pairError = text(this, "", 12f, RoomoteStyle.accent).apply { setPadding(0, dp(10), 0, dp(10)) }
        body.addView(pairError)
        val submit: () -> Unit = {
            val value = code.text.toString().trim()
            if (state.phase == ConnectionPhase.ERROR) {
                pairError?.text = ""
                code.text.clear()
                controller.pair()
            } else if (value.length != 6) {
                pairError?.text = "Enter all 6 characters shown on your TV."
                code.requestFocus()
                code.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
            } else {
                hideKeyboard(code)
                pairSubmit?.isEnabled = false
                pairSubmit?.text = "Checking code…"
                controller.submitPairingCode(value)
            }
        }
        pairSubmit = primaryAction("Connect TV", submit)
        body.addView(pairSubmit, LinearLayout.LayoutParams(-1, -2))
        code.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        body.addView(smallAction("Get a new code") {
            pairError?.text = ""
            code.text.clear()
            controller.pair()
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(text(this, "The pairing code stays between your phone and your TV.", 11f, RoomoteStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(6))
        })
        dialog.setOnCancelListener { controller.cancelPairing() }
        dialog.setOnDismissListener {
            openDialogs.remove(dialog)
            pairDialog = null
            pairError = null
            pairSubmit = null
            pairInput = null
        }
        dialog.showStyled()
    }

    private fun showHelp() {
        val (dialog, body) = createDialog("Make yourself at home", "A few small steps to connect your TV.")
        val steps = listOf(
            "01" to ("One network" to "Connect your phone and TV to the same Wi-Fi. Guest networks can prevent devices from seeing each other."),
            "02" to ("TV switched on" to "Keep your TV awake for discovery and pairing. Roomote works with compatible Android TV and Google TV devices."),
            "03" to ("Pair once, relax" to "Choose your TV, then enter the code it displays. Roomote remembers the TV securely on this phone."),
            "04" to ("Still not appearing?" to "Enter your TV’s IP address from its network settings. Check that Android TV Remote Service is installed and up to date.")
        )
        steps.forEach { (number, copy) ->
            val row = horizontal(this).apply { gravity = Gravity.TOP; setPadding(0, dp(14), 0, dp(6)) }
            row.addView(text(this, number, 12f, RoomoteStyle.accent, true), LinearLayout.LayoutParams(dp(32), -2).apply { topMargin = dp(3) })
            val info = vertical(this)
            info.addView(text(this, copy.first, 15f, bold = true))
            info.addView(text(this, copy.second, 12f, RoomoteStyle.muted).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
        }
        body.space(16)
        body.addView(text(this, "Power, input and media support varies by TV and app. A sleeping TV may need its physical remote to wake up.", 11f, RoomoteStyle.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        body.space(20)
        body.addView(primaryAction("Got it") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2))
        dialog.showStyled()
    }

    private fun showPrivacy() {
        val (dialog, body) = createDialog("Privacy & support", "Roomote is a free remote from crabfish.")
        body.addView(text(this,
            "Roomote discovers TVs and sends remote commands over your local network. " +
                "Your TV names, addresses and pairing credentials stay on this phone. " +
                "Pairing exchanges a public certificate with your TV; the private key stays in Android's secure keystore. " +
                "Roomote has no accounts, ads or analytics.",
            13f, RoomoteStyle.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        body.space(16)
        body.addView(text(this,
            "Use Forget TV to remove a saved TV and its local authorization. Clear Roomote's app data " +
                "or uninstall it to remove all local data. To revoke the phone on your TV too, remove it in the TV's settings.",
            13f, RoomoteStyle.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        body.space(16)
        body.addView(primaryAction("Read privacy policy") {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://roomote.crabfish.dev/privacy/")),
                "Open roomote.crabfish.dev/privacy/ in your browser.")
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(smallAction("Contact support") {
            openExternal(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:santu0002@gmail.com"))
                .putExtra(Intent.EXTRA_SUBJECT, "Roomote support"),
                "Email santu0002@gmail.com for support.")
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(text(this, "Support: santu0002@gmail.com", 12f, RoomoteStyle.muted).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(-1, -2))
        dialog.showStyled()
    }

    private fun openExternal(intent: Intent, fallback: String) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, fallback, Toast.LENGTH_LONG).show()
        }
    }

    private fun createDialog(title: String, subtitle: String): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        openDialogs.add(dialog)
        dialog.setOnDismissListener { openDialogs.remove(dialog) }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = rounded(RoomoteStyle.background, 28f, context = this@MainActivity)
        }
        val body = vertical(this).apply { setPadding(dp(24), dp(22), dp(24), dp(24)) }
        val heading = horizontal(this).apply { gravity = Gravity.TOP }
        heading.addView(text(this, title, 24f, bold = true).apply { letterSpacing = -0.035f }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(6) })
        val close = IconButton(this, Symbol.CLOSE, "Close", Color.TRANSPARENT, RoomoteStyle.muted, 24f, 19)
        close.setOnClickListener { dialog.cancel() }
        heading.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = -dp(12); topMargin = -dp(6) })
        body.addView(heading)
        body.addView(text(this, subtitle, 13f, RoomoteStyle.muted).apply { setPadding(0, dp(7), 0, dp(12)); setLineSpacing(dp(3).toFloat(), 1f) })
        scroll.addView(body)
        dialog.setContentView(scroll)
        return dialog to body
    }

    private fun Dialog.showStyled() {
        show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.28f)
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(32), dp(440)), -2)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun primaryAction(label: String, action: () -> Unit) = text(this, label, 14f, RoomoteStyle.white, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(52)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true
        isFocusable = true
        background = ripple(this@MainActivity, RoomoteStyle.ink, 16f)
        setOnClickListener { action() }
        asAccessibleButton()
    }

    private fun View.asAccessibleButton() {
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }

    private fun editText(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(RoomoteStyle.ink)
        setHintTextColor(RoomoteStyle.muted)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(RoomoteStyle.white, 15f, RoomoteStyle.border, this@MainActivity)
        setSelectAllOnFocus(true)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun LinearLayout.space(height: Int) = addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(height)))
}
