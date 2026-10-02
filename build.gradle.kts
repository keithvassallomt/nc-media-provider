plugins {
    // AGP 9 compiles Kotlin itself, so there is no separate Kotlin Android plugin.
    id("com.android.application") version "9.3.1" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("androidx.room") version "2.8.5" apply false
}
