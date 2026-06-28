package com.boringdroid.systemui.peek

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class PeekPanelWindow(
    private val pluginContext: Context,
    private val hostContext: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onRestore(taskId: Int)
        fun onMinimize(taskId: Int)
        fun onClose(taskId: Int)
    }

    private val windowManager =
        hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private val heightPx =
        pluginContext.resources.getDimensionPixelSize(
            pluginContext.resources.getIdentifier("peek_panel_height", "dimen", pluginContext.packageName)
        ).let { if (it <= 0) dpToPx(48f) else it }
    private val hideRunnable = Runnable { hide() }

    fun show(target: PeekTarget) {
        if (root != null) return
        val frame = FrameLayout(hostContext)
        frame.setOnHoverListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    handler.removeCallbacks(hideRunnable)
                    true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    handler.removeCallbacks(hideRunnable)
                    handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
                    true
                }
                else -> false
            }
        }

        val panel = createPanelContent(target)
        frame.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                heightPx,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.token = Binder()
        lp.title = "BoringdroidPeekPanel"
        frame.translationY = -heightPx.toFloat()
        windowManager.addView(frame, lp)
        root = frame
        frame.animate().translationY(0f).setDuration(ANIM_DURATION_MS).start()
    }

    fun hide() {
        val v = root ?: return
        handler.removeCallbacks(hideRunnable)
        v.animate()
            .translationY(-heightPx.toFloat())
            .setDuration(ANIM_DURATION_MS)
            .withEndAction {
                try {
                    windowManager.removeViewImmediate(v)
                } catch (_: IllegalArgumentException) {
                }
            }
            .start()
        root = null
    }

    private fun createPanelContent(target: PeekTarget): View {
        val density = pluginContext.resources.displayMetrics.density
        val dp = { value: Float -> (value * density + 0.5f).toInt() }

        val rootLayout = LinearLayout(hostContext)
        rootLayout.orientation = LinearLayout.HORIZONTAL
        rootLayout.setPadding(dp(12f), 0, dp(12f), 0)

        val background = GradientDrawable()
        background.setColor(Color.parseColor("#FF1C1B1F"))
        background.cornerRadius = dp(8f)
        rootLayout.background = background

        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        layoutParams.gravity = Gravity.CENTER_VERTICAL
        rootLayout.layoutParams = layoutParams

        val iconView = ImageView(hostContext)
        val iconSize = dp(20f)
        val iconLayout = LinearLayout.LayoutParams(iconSize, iconSize)
        iconLayout.gravity = Gravity.CENTER_VERTICAL
        iconView.layoutParams = iconLayout
        if (target.icon != null) {
            iconView.setImageDrawable(target.icon)
        }
        rootLayout.addView(iconView)

        val spacer1 = View(hostContext)
        spacer1.layoutParams = LinearLayout.LayoutParams(dp(8f), 0)
        rootLayout.addView(spacer1)

        val labelView = TextView(hostContext)
        labelView.text = target.label ?: ""
        labelView.setTextColor(Color.WHITE)
        labelView.textSize = 14f
        labelView.maxLines = 1
        val labelLayout = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
        labelLayout.weight = 1f
        labelLayout.gravity = Gravity.CENTER_VERTICAL
        labelView.layoutParams = labelLayout
        rootLayout.addView(labelView)

        val buttonContainer = LinearLayout(hostContext)
        buttonContainer.orientation = LinearLayout.HORIZONTAL
        val buttonContainerLayout = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        buttonContainerLayout.gravity = Gravity.CENTER_VERTICAL
        buttonContainer.layoutParams = buttonContainerLayout

        buttonContainer.addView(createCaptionButton("\u2014", "Minimize") {
            callbacks.onMinimize(target.taskId)
        })
        buttonContainer.addView(createCaptionButton("\u21F2", "Restore") {
            callbacks.onRestore(target.taskId)
        })
        buttonContainer.addView(createCaptionButton("\u2715", "Close") {
            callbacks.onClose(target.taskId)
        })

        rootLayout.addView(buttonContainer)
        return rootLayout
    }

    private fun createCaptionButton(text: String, contentDesc: String, onClick: () -> Unit): View {
        val density = pluginContext.resources.displayMetrics.density
        val dp = { value: Float -> (value * density + 0.5f).toInt() }

        val btn = object : Button(hostContext, null, android.R.attr.borderlessButtonStyle) {
            override fun onHoverEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> alpha = 0.7f
                    MotionEvent.ACTION_HOVER_EXIT -> alpha = 1.0f
                }
                return super.onHoverEvent(event)
            }
        }
        btn.text = text
        btn.setTextColor(Color.WHITE)
        btn.textSize = 16f
        btn.contentDescription = contentDesc
        val btnSize = dp(40f)
        btn.layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
        btn.setOnClickListener { onClick() }
        val btnBg = GradientDrawable()
        btnBg.setShape(GradientDrawable.RECTANGLE)
        btnBg.setColor(Color.TRANSPARENT)
        btn.background = btnBg
        return btn
    }

    private fun dpToPx(dp: Float): Int {
        val density = pluginContext.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    companion object {
        private const val ANIM_DURATION_MS = 150L
        private const val AUTO_HIDE_MS = 400L
    }
}
