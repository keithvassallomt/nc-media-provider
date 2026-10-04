package com.keithvassallo.ncmediaprovider.activation;

interface IActivationService {
    void destroy() = 16777114;

    // Returns "active" when MediaProvider applied it, or "restart" when the phone reads the
    // cloud media flag only at startup (GrapheneOS).
    String activate(boolean keepGooglePhotos) = 1;

    // Selects this app as the picker's cloud source; true when MediaProvider confirms it.
    boolean selectProvider() = 2;

    // Undoes activate(): selects Google Photos again where it can, then clears this app's
    // overrides. Returns "cleared" once MediaProvider no longer lists this app.
    String deactivate() = 3;
}
