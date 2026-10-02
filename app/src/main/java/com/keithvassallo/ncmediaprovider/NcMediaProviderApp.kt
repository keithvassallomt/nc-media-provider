package com.keithvassallo.ncmediaprovider

import android.app.Application
import com.keithvassallo.ncmediaprovider.data.DebugAccount

class NcMediaProviderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugAccount.seed(this)
    }
}
