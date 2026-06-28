package com.boringdroid.systemui

import android.content.Context
import android.util.Log
import android.view.View
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires
import com.boringdroid.systemui.peek.PeekCaptionController

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class SystemUIOverlay : OverlayPlugin {
    private var pluginContext: Context? = null
    private var peekCaptionController: PeekCaptionController? = null

    override fun setup(statusBar: View, navBar: View) {
    }

    override fun holdStatusBarOpen(): Boolean = false

    override fun setCollapseDesired(collapseDesired: Boolean) {
    }

    override fun onCreate(sysUIContext: Context, pluginContext: Context) {
        this.pluginContext = pluginContext
        peekCaptionController = PeekCaptionController(pluginContext, sysUIContext).apply { start() }
    }

    override fun onDestroy() {
        peekCaptionController?.stop()
        peekCaptionController = null
        pluginContext = null
    }
}
