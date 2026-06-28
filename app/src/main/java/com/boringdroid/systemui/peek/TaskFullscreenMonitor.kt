package com.boringdroid.systemui.peek

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.UserManager
import android.window.WindowContainerToken
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners

data class PeekTarget(
    val taskId: Int,
    val token: WindowContainerToken,
    val component: ComponentName?,
    val icon: Drawable?,
    val label: CharSequence?,
    val currentMode: Int,
    val currentBounds: Rect,
    val displayMode: Int
)

class TaskFullscreenMonitor(
    private val pluginContext: Context
) {
    private val activityManager =
        pluginContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val launcherApps =
        pluginContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager = pluginContext.getSystemService(Context.USER_SERVICE) as UserManager
    private val taskListeners = TaskStackChangeListeners(Looper.getMainLooper())
    private val listener = MonitorListener()

    private val previousMode = HashMap<Int, Int>()
    private val maximizedFromFreeform = HashSet<Int>()

    @Volatile
    private var _peekTarget: PeekTarget? = null

    val peekTarget: PeekTarget?
        get() = _peekTarget

    private var started = false
    private var callback: ((PeekTarget?) -> Unit)? = null

    fun setOnPeekTargetChanged(listener: (PeekTarget?) -> Unit) {
        callback = listener
    }

    fun start() {
        if (started) return
        started = true
        taskListeners.addListener(ActivityManager.getService(), listener)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        taskListeners.removeListener(listener)
        previousMode.clear()
        maximizedFromFreeform.clear()
        _peekTarget = null
        callback?.invoke(null)
    }

    fun refresh() {
        val running = activityManager.getRunningTasks(MAX_TASKS)
        val seenIds = HashSet<Int>(running.size)
        for (info in running) {
            val id = info.taskId
            seenIds.add(id)
            val prev = previousMode[id]
            val current = info.configuration.windowConfiguration.windowingMode
            if (prev == WindowConfiguration.WINDOWING_MODE_FREEFORM &&
                current == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
            ) {
                maximizedFromFreeform.add(id)
            } else if (current == WindowConfiguration.WINDOWING_MODE_FREEFORM) {
                maximizedFromFreeform.remove(id)
            }
            previousMode[id] = current
        }
        previousMode.keys.retainAll(seenIds)
        maximizedFromFreeform.retainAll(seenIds)
        val top = running.firstOrNull()
        val newTarget =
            if (top != null &&
                top.taskId in maximizedFromFreeform &&
                top.configuration.windowConfiguration.windowingMode ==
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN
            ) {
                buildPeekTarget(top)
            } else {
                null
            }
        _peekTarget = newTarget
        callback?.invoke(newTarget)
    }

    private fun buildPeekTarget(info: ActivityManager.RunningTaskInfo): PeekTarget? {
        val token = info.token ?: return null
        val pkg = info.baseActivity?.packageName
        val icon = if (pkg != null) resolveIcon(pkg) else null
        val label = if (pkg != null) resolveLabel(pkg) else null
        return PeekTarget(
            taskId = info.taskId,
            token = token,
            component = info.topActivity,
            icon = icon,
            label = label,
            currentMode = info.configuration.windowConfiguration.windowingMode,
            currentBounds = Rect(info.configuration.windowConfiguration.bounds),
            displayMode = info.configuration.windowConfiguration.displayWindowingMode
        )
    }

    private fun resolveIcon(pkg: String): Drawable? {
        for (user in userManager.userProfiles) {
            val list = launcherApps.getActivityList(pkg, user)
            if (!list.isNullOrEmpty()) {
                return list[0].getIcon(0)
            }
        }
        return null
    }

    private fun resolveLabel(pkg: String): CharSequence? =
        try {
            val pm = pluginContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }

    private inner class MonitorListener : TaskStackChangeListener {
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            refresh()
        }

        override fun onTaskMovedToFront(taskId: Int) {
            refresh()
        }

        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            refresh()
        }

        override fun onTaskStackChanged() {
            refresh()
        }

        override fun onTaskRemoved(taskId: Int) {
            refresh()
        }
    }

    companion object {
        private const val MAX_TASKS = 50
    }
}
