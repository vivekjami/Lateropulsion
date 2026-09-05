package com.lateropulsion.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lateropulsion.app.logging.LogcatSink
import com.lateropulsion.core.common.LogLevel
import com.lateropulsion.core.common.LpLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LateropulsionApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        LpLog.addSink(LogcatSink())
        LpLog.minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO
        LpLog.strictPhiGuard = true
        LpLog.i("App", "start", "version" to BuildConfig.VERSION_NAME, "flavor" to BuildConfig.FLAVOR)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
