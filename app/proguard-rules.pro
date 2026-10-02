-keepattributes *Annotation*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Shizuku instantiates this user service by class name in a separate process.
-keep class com.keithvassallo.ncmediaprovider.activation.DeviceConfigUserService { *; }
