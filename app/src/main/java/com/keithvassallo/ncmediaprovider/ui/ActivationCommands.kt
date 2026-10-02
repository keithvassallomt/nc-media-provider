package com.keithvassallo.ncmediaprovider.ui

import com.keithvassallo.ncmediaprovider.activation.CloudProviderActivation

object ActivationCommands {
    fun enable(packageName: String): String = CloudProviderActivation.enableCommands(packageName)

    fun restore(packageName: String): String = CloudProviderActivation.restoreCommands(packageName)
}
