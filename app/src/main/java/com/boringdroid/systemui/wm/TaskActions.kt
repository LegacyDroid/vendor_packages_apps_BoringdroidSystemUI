package com.boringdroid.systemui.wm

import android.annotation.SuppressLint
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import android.window.WindowOrganizer

class TaskActions(
    private val pluginContext: Context,
    private val hostContext: Context,
    private val onWctApplied: () -> Unit = {},
) {
    private val windowOrganizer = WindowOrganizer()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun close(token: WindowContainerToken) {
        val wct = WindowContainerTransaction().removeTask(token)
        apply(wct, "close")
    }

    fun minimize(token: WindowContainerToken) {
        val wct = WindowContainerTransaction().reorder(token, /* onTop= */ false)
        apply(wct, "minimize")
        val home =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            hostContext.startActivity(home)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not launch home as minimise fallback", e)
        }
    }

    fun toggleMaximize(
        token: WindowContainerToken,
        currentMode: Int,
        currentBounds: Rect,
        displayMode: Int,
    ) {
        val wct = WindowContainerTransaction()
        if (isDesktopModeEnabled()) {
            val stable = stableDisplayBounds()
            if (currentBounds == stable) {
                wct.setBounds(token, defaultDesktopBounds(stable))
            } else {
                wct.setBounds(token, stable)
            }
        } else {
            val target =
                if (currentMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                    WindowConfiguration.WINDOWING_MODE_FREEFORM
                } else {
                    WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                }
            val effectiveTarget =
                if (target == displayMode) WindowConfiguration.WINDOWING_MODE_UNDEFINED
                else target
            wct.setWindowingMode(token, effectiveTarget)
            if (target == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                wct.setBounds(token, null)
            }
        }
        apply(wct, "toggleMaximize")
        mainHandler.postDelayed(onWctApplied, WCT_OBSERVE_DELAY_MS)
    }

    private fun stableDisplayBounds(): Rect {
        val wm = pluginContext.getSystemService(WindowManager::class.java)
        val metrics = wm.maximumWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
        val bounds = Rect(metrics.bounds)
        bounds.left += insets.left
        bounds.top += insets.top
        bounds.right -= insets.right
        bounds.bottom -= insets.bottom
        return bounds
    }

    private fun defaultDesktopBounds(stable: Rect): Rect {
        val density =
            pluginContext.resources.displayMetrics.densityDpi.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT
        val width = (DESKTOP_MODE_DEFAULT_WIDTH_DP * density + 0.5f).toInt()
        val height = (DESKTOP_MODE_DEFAULT_HEIGHT_DP * density + 0.5f).toInt()
        val bounds = Rect(0, 0, width, height)
        bounds.offset(stable.centerX() - bounds.centerX(), stable.centerY() - bounds.centerY())
        return bounds
    }

    @SuppressLint("PrivateApi")
    private fun isDesktopModeEnabled(): Boolean =
        readBoolProp(DESKTOP_MODE_PROTO1_PROP, defaultValue = false) ||
            readBoolProp(DESKTOP_MODE_PROTO2_PROP, defaultValue = false)

    @SuppressLint("PrivateApi")
    private fun readBoolProp(key: String, defaultValue: Boolean): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get =
                cls.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            get.invoke(null, key, defaultValue) as Boolean
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "could not read $key; defaulting to $defaultValue", e)
            defaultValue
        }
    }

    private fun apply(wct: WindowContainerTransaction, label: String) {
        try {
            windowOrganizer.applyTransaction(wct)
        } catch (e: RuntimeException) {
            Log.w(TAG, "$label transaction failed", e)
        }
    }

    companion object {
        private const val TAG = "TaskActions"
        private const val DESKTOP_MODE_PROTO1_PROP = "persist.wm.debug.desktop_mode"
        private const val DESKTOP_MODE_PROTO2_PROP = "persist.wm.debug.desktop_mode_2"
        private const val DESKTOP_MODE_DEFAULT_WIDTH_DP = 840
        private const val DESKTOP_MODE_DEFAULT_HEIGHT_DP = 630
        private const val WCT_OBSERVE_DELAY_MS = 150L
    }
}
