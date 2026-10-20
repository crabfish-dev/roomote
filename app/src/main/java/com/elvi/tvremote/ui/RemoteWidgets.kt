package com.elvi.tvremote.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

object RoomoteStyle {
    val ink = Color.rgb(31, 43, 54)
    val muted = Color.rgb(95, 103, 103)
    val background = Color.rgb(248, 247, 242)
    val surface = Color.rgb(239, 239, 232)
    val border = Color.rgb(223, 225, 217)
    val accent = Color.rgb(166, 75, 46)
    val accentLight = Color.rgb(250, 231, 218)
    val white = Color.rgb(255, 255, 252)
    val green = Color.rgb(67, 112, 86)
}

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float = 24f, stroke: Int? = null, context: Context): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = context.resources.displayMetrics.density * radius
        if (stroke != null) setStroke(context.dp(1), stroke)
    }

fun ripple(context: Context, color: Int, radius: Float = 24f, stroke: Int? = null) =
    RippleDrawable(
        ColorStateList.valueOf(Color.argb(32, 31, 43, 54)),
        rounded(color, radius, stroke, context),
        rounded(Color.WHITE, radius, context = context)
    )

fun text(context: Context, value: String, size: Float, color: Int = RoomoteStyle.ink, bold: Boolean = false) =
    TextView(context).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

enum class Symbol {
    TV, WIFI, CHEVRON_UP, CHEVRON_DOWN, CHEVRON_LEFT, CHEVRON_RIGHT,
    BACK, HOME, MENU, PLUS, MINUS, MUTE, VOLUME, PLAY_PAUSE, PREVIOUS, NEXT,
    POWER, INPUT, CLOSE, REFRESH, CHECK, LINK, INFO
}

/** Small vector icons, drawn on a 24×24 grid so controls remain crisp at every density. */
class SymbolView(context: Context, var symbol: Symbol, var tint: Int = RoomoteStyle.ink) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        paint.color = tint
        paint.style = Paint.Style.STROKE
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        fun path(vararg points: Float, close: Boolean = false, fill: Boolean = false) {
            val p = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                if (close) close()
            }
            paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawPath(p, paint)
            paint.style = Paint.Style.STROKE
        }
        when (symbol) {
            Symbol.TV -> {
                canvas.drawRoundRect(3f, 4f, 21f, 17f, 2f, 2f, paint)
                line(8f, 21f, 16f, 21f)
                line(12f, 17f, 12f, 21f)
            }
            Symbol.WIFI -> {
                canvas.drawArc(RectF(1f, 5f, 23f, 27f), 222f, 96f, false, paint)
                canvas.drawArc(RectF(5f, 10f, 19f, 24f), 225f, 90f, false, paint)
                canvas.drawArc(RectF(9f, 15f, 15f, 21f), 230f, 80f, false, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 21f, 1f, paint)
            }
            Symbol.CHEVRON_UP -> path(6f, 15f, 12f, 9f, 18f, 15f)
            Symbol.CHEVRON_DOWN -> path(6f, 9f, 12f, 15f, 18f, 9f)
            Symbol.CHEVRON_LEFT -> path(15f, 6f, 9f, 12f, 15f, 18f)
            Symbol.CHEVRON_RIGHT -> path(9f, 6f, 15f, 12f, 9f, 18f)
            Symbol.BACK -> {
                path(9f, 5f, 3f, 11f, 9f, 17f)
                val p = Path().apply {
                    moveTo(3f, 11f)
                    lineTo(14f, 11f)
                    quadTo(21f, 11f, 21f, 18f)
                }
                canvas.drawPath(p, paint)
            }
            Symbol.HOME -> {
                path(3f, 10f, 12f, 3f, 21f, 10f)
                path(5f, 9f, 5f, 21f, 9f, 21f, 9f, 15f, 15f, 15f, 15f, 21f, 19f, 21f, 19f, 9f)
            }
            Symbol.MENU -> {
                line(5f, 6f, 19f, 6f); line(5f, 12f, 19f, 12f); line(5f, 18f, 19f, 18f)
            }
            Symbol.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Symbol.MINUS -> line(5f, 12f, 19f, 12f)
            Symbol.MUTE, Symbol.VOLUME -> {
                path(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, close = true)
                if (symbol == Symbol.MUTE) {
                    line(17f, 9f, 22f, 15f); line(22f, 9f, 17f, 15f)
                } else {
                    canvas.drawArc(RectF(9f, 7f, 19f, 17f), -60f, 120f, false, paint)
                    canvas.drawArc(RectF(5f, 3f, 23f, 21f), -55f, 110f, false, paint)
                }
            }
            Symbol.PLAY_PAUSE -> {
                path(3f, 5f, 3f, 19f, 12f, 12f, close = true, fill = true)
                line(17f, 6f, 17f, 18f); line(21f, 6f, 21f, 18f)
            }
            Symbol.PREVIOUS -> {
                line(5f, 5f, 5f, 19f)
                path(19f, 5f, 19f, 19f, 9f, 12f, close = true, fill = true)
            }
            Symbol.NEXT -> {
                line(19f, 5f, 19f, 19f)
                path(5f, 5f, 5f, 19f, 15f, 12f, close = true, fill = true)
            }
            Symbol.POWER -> {
                canvas.drawArc(RectF(3f, 3f, 21f, 21f), -50f, 280f, false, paint)
                line(12f, 2f, 12f, 11f)
            }
            Symbol.INPUT -> {
                path(12f, 4f, 21f, 4f, 21f, 20f, 12f, 20f)
                line(2f, 12f, 15f, 12f)
                path(10f, 7f, 15f, 12f, 10f, 17f)
            }
            Symbol.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Symbol.REFRESH -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), 40f, 290f, false, paint)
                path(20f, 3f, 20f, 8f, 15f, 8f)
            }
            Symbol.CHECK -> path(4f, 12f, 9f, 17f, 20f, 6f)
            Symbol.LINK -> {
                canvas.drawRoundRect(2f, 8f, 13f, 16f, 4f, 4f, paint)
                canvas.drawRoundRect(11f, 8f, 22f, 16f, 4f, 4f, paint)
            }
            Symbol.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                line(12f, 11f, 12f, 17f)
                canvas.drawPoint(12f, 7f, paint)
            }
        }
        canvas.restore()
    }
}

class IconButton(
    context: Context,
    icon: Symbol,
    label: String,
    color: Int = RoomoteStyle.surface,
    tint: Int = RoomoteStyle.ink,
    radius: Float = 20f,
    iconSize: Int = 24
) : FrameLayout(context) {
    val symbolView = SymbolView(context, icon, tint)
    init {
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = ripple(context, color, radius)
        addView(symbolView, LayoutParams(context.dp(iconSize), context.dp(iconSize), Gravity.CENTER))
        minimumHeight = context.dp(48)
        minimumWidth = context.dp(48)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

class DPad(context: Context, onKey: (Int) -> Unit) : FrameLayout(context) {
    val keyButtons = mutableListOf<View>()
    init {
        background = rounded(RoomoteStyle.surface, 160f, RoomoteStyle.border, context)
        val positions = listOf(
            Triple(Symbol.CHEVRON_UP, android.view.KeyEvent.KEYCODE_DPAD_UP, "Up"),
            Triple(Symbol.CHEVRON_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN, "Down"),
            Triple(Symbol.CHEVRON_LEFT, android.view.KeyEvent.KEYCODE_DPAD_LEFT, "Left"),
            Triple(Symbol.CHEVRON_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, "Right")
        )
        positions.forEachIndexed { index, (symbol, code, label) ->
            val button = IconButton(context, symbol, label, Color.TRANSPARENT, radius = 36f)
            val gravity = when (index) {
                0 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                1 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                2 -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                else -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            addView(button, LayoutParams(context.dp(64), context.dp(64), gravity).apply {
                val margin = context.dp(4)
                setMargins(margin, margin, margin, margin)
            })
            button.setOnClickListener { onKey(code) }
            keyButtons += button
        }
        val ok = text(context, "OK", 21f, RoomoteStyle.white, true).apply {
            gravity = Gravity.CENTER
            background = ripple(context, RoomoteStyle.ink, 50f)
            isClickable = true
            isFocusable = true
            contentDescription = "Select"
            setOnClickListener { onKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER) }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            }
        }
        addView(ok, LayoutParams(context.dp(88), context.dp(88), Gravity.CENTER))
        keyButtons += ok
    }
}

fun vertical(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

fun horizontal(context: Context) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}
