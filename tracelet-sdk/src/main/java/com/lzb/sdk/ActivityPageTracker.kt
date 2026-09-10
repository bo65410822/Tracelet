package com.lzb.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lzb.core.TraceletContext

internal class ActivityPageTracker : Application.ActivityLifecycleCallbacks {

    private val mActivityStack = ArrayDeque<Activity>()

    private val mFragmentStack = ArrayDeque<Fragment>()

    private val mFragmentTracker = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            super.onFragmentResumed(fm, f)
            if (!f.isTrackable()) return
            mFragmentStack.remove(f)
            mFragmentStack.addLast(f)
            updateCurrentPage()
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            super.onFragmentPaused(fm, f)
            if (mFragmentStack.remove(f)) {
                updateCurrentPage()
            }
        }

        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
            super.onFragmentDetached(fm, f)
            if (mFragmentStack.remove(f)) {
                updateCurrentPage()
            }
        }
    }

    private fun Fragment.isTrackable(): Boolean {
        if (view == null) return false
        return !javaClass.name.startsWith("androidx.")
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {
        (p0 as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(
            mFragmentTracker,
            true
        )
    }

    override fun onActivityStarted(p0: Activity) {
    }

    override fun onActivityResumed(p0: Activity) {
        mActivityStack.remove(p0)
        mActivityStack.addLast(p0)
        updateCurrentPage()
    }

    override fun onActivityPaused(p0: Activity) {
    }

    override fun onActivityStopped(p0: Activity) {
        mActivityStack.remove(p0)
        updateCurrentPage()
    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {
    }

    override fun onActivityDestroyed(p0: Activity) {
        mActivityStack.remove(p0)
        (p0 as? FragmentActivity)?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(
            mFragmentTracker
        )
        updateCurrentPage()
    }

    private fun updateCurrentPage() {
        val actName = mActivityStack.lastOrNull()?.javaClass?.simpleName
        val frgName = mFragmentStack.lastOrNull()?.javaClass?.simpleName
        TraceletContext.currentPage = (actName ?: "") + "/" + (frgName ?: "")
    }
}