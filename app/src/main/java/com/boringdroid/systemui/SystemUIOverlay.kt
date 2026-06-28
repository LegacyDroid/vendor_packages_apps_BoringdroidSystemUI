package com.boringdroid.systemui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires
import com.boringdroid.systemui.peek.PeekCaptionController
import java.lang.reflect.InvocationTargetException
import java.util.Arrays
import java.util.stream.Collectors

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class SystemUIOverlay : OverlayPlugin {
    private var pluginContext: Context? = null
    private var systemUIContext: Context? = null
    private var navBarRef: View? = null
    private var btAllAppsGroup: ViewGroup? = null
    private var appStateLayout: AppStateLayout? = null
    private var btAllApps: View? = null
    private var allAppsWindow: AllAppsWindow? = null
    private var navBarButtonGroupId = -1
    private var resolver: ContentResolver? = null
    private var peekCaptionController: PeekCaptionController? = null
    private val tunerKeys: MutableList<String> = ArrayList()
    private val tunerKeyObserver: ContentObserver = TunerKeyObserver()
    private val closeSystemDialogsReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "receive intent $intent")
            if (allAppsWindow == null) {
                return
            }
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS != intent.action) {
                return
            }
            allAppsWindow!!.dismiss()
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        navBarRef?.let { injectIntoVisibleEndsGroup(it) }
    }

    override fun setup(statusBar: View, navBar: View) {
        Log.d(TAG, "setup status bar $statusBar, nav bar $navBar")
        navBarRef = navBar
        if (navBarButtonGroupId > 0) {
            navBar.removeOnLayoutChangeListener(layoutChangeListener)
            injectIntoVisibleEndsGroup(navBar)
            navBar.addOnLayoutChangeListener(layoutChangeListener)
        }
    }

    private fun injectIntoVisibleEndsGroup(navBar: View) {
        if (navBarButtonGroupId <= 0) return
        val visibleGroup = findVisibleEndsGroup(navBar, navBarButtonGroupId)
        if (visibleGroup == null) {
            Log.w(TAG, "no visible ends_group found, injecting into first match")
            val fallback = navBar.findViewById<View>(navBarButtonGroupId)
            if (fallback is ViewGroup) {
                injectIntoGroup(fallback)
            }
            return
        }
        injectIntoGroup(visibleGroup)
    }

    @SuppressLint("InflateParams")
    private fun injectIntoGroup(group: ViewGroup) {
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val allAppsParent = btAllAppsGroup?.parent as? ViewGroup
        allAppsParent?.removeView(btAllAppsGroup)
        btAllAppsGroup!!.tag = TAG_ALL_APPS_GROUP
        group.addView(btAllAppsGroup, 0, layoutParams)

        val stateParent = appStateLayout?.parent as? ViewGroup
        stateParent?.removeView(appStateLayout)
        appStateLayout!!.tag = TAG_APP_STATE_LAYOUT
        group.addView(appStateLayout, 4, layoutParams)
        appStateLayout!!.initTasks()
    }

    private fun findVisibleEndsGroup(root: View, id: Int): ViewGroup? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.id == id && child.isShown && child is ViewGroup) {
                    return child
                }
                val found = findVisibleEndsGroup(child, id)
                if (found != null) return found
            }
        }
        return null
    }

    override fun holdStatusBarOpen(): Boolean {
        return false
    }

    override fun setCollapseDesired(collapseDesired: Boolean) {
        // Do nothing
    }

    override fun onCreate(sysUIContext: Context, pluginContext: Context) {
        systemUIContext = sysUIContext
        this.pluginContext = pluginContext
        navBarButtonGroupId = sysUIContext
            .resources
            .getIdentifier("ends_group", "id", "com.android.systemui")
        btAllAppsGroup = initializeAllAppsButton(this.pluginContext, btAllAppsGroup)
        appStateLayout = initializeAppStateLayout(this.pluginContext, appStateLayout)
        appStateLayout!!.reloadActivityManager(systemUIContext)
        btAllApps = btAllAppsGroup!!.findViewById(R.id.bt_all_apps)
        allAppsWindow = AllAppsWindow(this.pluginContext)
        btAllApps!!.setOnClickListener(allAppsWindow)
        resolver = sysUIContext.contentResolver
        initializeTuningServiceSettingKeys(resolver, tunerKeyObserver)
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        systemUIContext!!.registerReceiver(closeSystemDialogsReceiver, filter)
        peekCaptionController = PeekCaptionController(this.pluginContext!!, sysUIContext).apply { start() }
    }

    override fun onDestroy() {
        navBarRef?.removeOnLayoutChangeListener(layoutChangeListener)
        navBarRef = null
        peekCaptionController?.stop()
        peekCaptionController = null
        removeViewsFromEndsGroups()
        if (systemUIContext != null) {
            try {
                systemUIContext!!.unregisterReceiver(closeSystemDialogsReceiver)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Try to unregister close system dialogs receiver without registering")
            }
        }
        if (resolver != null) {
            resolver!!.unregisterContentObserver(tunerKeyObserver)
        }
        pluginContext = null
    }

    private fun removeViewsFromEndsGroups() {
        val navBar = navBarRef ?: return
        removeFromAllGroups(navBar, navBarButtonGroupId, TAG_ALL_APPS_GROUP)
        removeFromAllGroups(navBar, navBarButtonGroupId, TAG_APP_STATE_LAYOUT)
    }

    private fun removeFromAllGroups(root: View, groupId: Int, tag: String) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.id == groupId && child is ViewGroup) {
                    val tagged = child.findViewWithTag<View>(tag)
                    if (tagged != null) {
                        child.removeView(tagged)
                    }
                }
                removeFromAllGroups(child, groupId, tag)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun initializeTuningServiceSettingKeys(
        resolver: ContentResolver?,
        observer: ContentObserver
    ) {
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod =
                systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            val tunerKeys = getMethod.invoke(null, "persist.sys.bd.tunerkeys", "") as String
            Log.d(TAG, "Got tuner keys $tunerKeys")
            val tunerKeyList = Arrays.stream(tunerKeys.split("--").toTypedArray())
                .map { obj: String -> obj.trim { it <= ' ' } }
                .filter { key: String -> !key.isEmpty() }
                .collect(Collectors.toList())
            this.tunerKeys.clear()
            this.tunerKeys.addAll(tunerKeyList)
            for (key in this.tunerKeys) {
                Log.d(TAG, "Got key $key")
                val uri = Settings.Secure.getUriFor(key)
                resolver!!.registerContentObserver(uri, false, observer)
            }
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Failed to get tuner keys from properties, so fallback to default")
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Failed to get tuner keys from properties, so fallback to default")
        } catch (e: IllegalAccessException) {
            Log.e(TAG, "Failed to get tuner keys from properties, so fallback to default")
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "Failed to get tuner keys from properties, so fallback to default")
        }
    }

    @SuppressLint("InflateParams")
    private fun initializeAllAppsButton(context: Context?, btAllAppsGroup: ViewGroup?): ViewGroup {
        return btAllAppsGroup
            ?: LayoutInflater.from(context)
                .inflate(R.layout.layout_bt_all_apps, null) as ViewGroup
    }

    @SuppressLint("InflateParams")
    private fun initializeAppStateLayout(
        context: Context?,
        appStateLayout: AppStateLayout?
    ): AppStateLayout {
        return appStateLayout
            ?: LayoutInflater.from(context)
                .inflate(R.layout.layout_app_state, null) as AppStateLayout
    }

    private fun onTunerChange(uri: Uri) {
        val keyName = uri.lastPathSegment
        val value = Settings.Secure.getString(resolver, keyName)
        Log.d(TAG, "onTunerChange $uri, value $value")
        val packageUri = Uri.fromParts("package", pluginContext!!.packageName, null)
        Log.d(TAG, "onTunerChange packageUri $packageUri")
        val pluginChangedIntent = Intent(ACTION_PLUGIN_CHANGED, packageUri)
        pluginContext!!.sendBroadcast(pluginChangedIntent)
    }

    private inner class TunerKeyObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Log.d(TAG, "TunerKeyChanged $uri, self changed $selfChange")
            onTunerChange(uri!!)
        }
    }

    companion object {
        private const val TAG = "SystemUIOverlay"

        // Copied from systemui source code, please keep it update to source code.
        private const val ACTION_PLUGIN_CHANGED = "com.android.systemui.action.PLUGIN_CHANGED"
        private const val TAG_ALL_APPS_GROUP = "tag-bt-all-apps-group"
        private const val TAG_APP_STATE_LAYOUT = "tag-app-state-layout"
    }
}
