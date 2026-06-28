package com.boringdroid.systemui

import android.app.WindowConfiguration
import android.content.ComponentName
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.window.WindowContainerToken

class TaskInfo {
    var id = 0
    private var baseActivityComponentName: ComponentName? = null
    private var realActivityComponentName: ComponentName? = null
    var packageName: String? = null
    var icon: Drawable? = null
    var token: WindowContainerToken? = null
    var windowingMode: Int = WindowConfiguration.WINDOWING_MODE_UNDEFINED
    var bounds: Rect? = null

    fun setBaseActivityComponentName(baseActivityComponentName: ComponentName?) {
        this.baseActivityComponentName = baseActivityComponentName
    }

    fun setRealActivityComponentName(realActivityComponentName: ComponentName?) {
        this.realActivityComponentName = realActivityComponentName
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TaskInfo) {
            return false
        }
        return id == other.id
    }

    override fun hashCode(): Int {
        return id
    }

    override fun toString(): String {
        return ("Task id " +
                id +
                ", origin " +
                baseActivityComponentName +
                ", real " +
                realActivityComponentName +
                ", package " +
                packageName)
    }
}
