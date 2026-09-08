package com.lzb.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lzb.core.TraceletContext

internal class ActivityPageTracker : Application.ActivityLifecycleCallbacks {

    private val mActivityStack = ArrayDeque<Activity>()

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {

    }

    override fun onActivityStarted(p0: Activity) {
    }

    override fun onActivityResumed(p0: Activity) {
        mActivityStack.remove(p0)
        mActivityStack.addLast(p0)
        TraceletContext.currentPage = mActivityStack.last().javaClass.simpleName
    }

    override fun onActivityPaused(p0: Activity) {
    }

    override fun onActivityStopped(p0: Activity) {
        mActivityStack.remove(p0)
        TraceletContext.currentPage = mActivityStack.lastOrNull()?.javaClass?.simpleName ?: ""
    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {
    }

    override fun onActivityDestroyed(p0: Activity) {
        mActivityStack.remove(p0)
        TraceletContext.currentPage = mActivityStack.lastOrNull()?.javaClass?.simpleName ?: ""
    }
}