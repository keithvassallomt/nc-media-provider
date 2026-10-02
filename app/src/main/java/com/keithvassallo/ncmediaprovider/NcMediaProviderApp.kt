package com.keithvassallo.ncmediaprovider

import android.app.Application
import com.keithvassallo.ncmediaprovider.data.DebugAccount
import com.keithvassallo.ncmediaprovider.data.LibraryRepository

class NcMediaProviderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugAccount.seed(this)
        LibraryRepository.get(this).schedulePeriodicSync()
    }
}
