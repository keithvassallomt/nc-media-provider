Problem:
 - Messenger, Facebook and other apps are written by idiots. 
 - Because of this, they don't use the system photo picker. Instead, they implement their own picker which can only read from MediaStore. It can't access photos provided by CloudMediaProvider. 

Workaround:
 - We add an option in our app to sync a limited number of Photos to MediaStore. Say, for eample, "Last 100 Photos". This way, users can still access their recent photos through the app's custom picker, even if they are stored in the cloud. For everything else, they can use the share sheet we talked about earlier. 
 - We'd of course need to make sure we don't sync photos which are already on the device.

Determination:
 - Is this an ideal solution? Fuck no.
 - Is there an alternative. Also no.
 - Is this a solution that works for now? Yes.