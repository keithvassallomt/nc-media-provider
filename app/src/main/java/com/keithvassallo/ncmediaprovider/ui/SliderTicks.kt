package com.keithvassallo.ncmediaprovider.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Labelled marks under a slider, at fractions of its track: where the pre-cache's date shortcuts
 * fall on the size slider (PLAN 5.6). Decorative; the shortcut buttons say the same.
 */
class SliderTicks @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var ticks: List<Pair<Float, String>> = emptyList()

    /** The slider's track padding on each side, so marks line up with the thumb. */
    var inset: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SliderTicks, com.google.android.material.R.attr.colorOutline)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SliderTicks, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setTicks(ticks: List<Pair<Float, String>>) {
        this.ticks = ticks
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (dp(MARK_DP + GAP_DP) + textPaint.fontSpacing).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val track = (width - 2 * inset).coerceAtLeast(1)
        val markTop = paddingTop.toFloat()
        val markBottom = markTop + dp(MARK_DP)
        val baseline = markBottom + dp(GAP_DP) - textPaint.ascent()
        for ((fraction, label) in ticks) {
            val x = inset + fraction.coerceIn(0f, 1f) * track
            canvas.drawLine(x, markTop, x, markBottom, markPaint)
            canvas.drawText(label, x, baseline, textPaint)
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        const val MARK_DP = 6f
        const val GAP_DP = 2f
    }
}
