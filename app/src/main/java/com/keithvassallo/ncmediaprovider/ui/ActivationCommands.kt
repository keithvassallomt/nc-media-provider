package com.keithvassallo.ncmediaprovider.ui

import com.keithvassallo.ncmediaprovider.activation.CloudProviderActivation

object ActivationCommands {
    fun enable(packageName: String, keepGooglePhotos: Boolean = true): String =
        CloudProviderActivation.enableCommands(packageName, keepGooglePhotos)

    fun restore(packageName: String): String = CloudProviderActivation.restoreCommands(packageName)
}
