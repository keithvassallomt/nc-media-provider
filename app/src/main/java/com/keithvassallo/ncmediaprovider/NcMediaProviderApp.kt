package com.keithvassallo.ncmediaprovider

import android.app.Application
import com.keithvassallo.ncmediaprovider.data.LibraryRepository

class NcMediaProviderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LibraryRepository.get(this).schedulePeriodicSync()
    }
}
