# Privacy policy

**NC Media Provider**, an open-source Android app by Keith Vassallo.
Effective 4 October 2026.

## In short

NC Media Provider collects no data. It has no servers of its own, and it contains no ads, analytics, crash reporting or tracking. It talks only to the Nextcloud server you sign in to, and everything it keeps stays on your phone.

## What the app uses, and why

**Your Nextcloud server.** You give the app your server's address and sign in with Nextcloud's own login page in your browser. Nextcloud then gives the app an app password, which is stored on your phone, encrypted with a key held in Android's keystore. The app uses it only to talk to your server. Signing out in the app deletes the app password from your server and removes it from the phone; you can also revoke it in Nextcloud under Settings > Security.

**Your photos and videos on that server.** The app lists the photos and videos in the folders you choose, with their names, dates, sizes and albums, and reads extra details from the Memories and Photos apps on your server when they are installed. It downloads thumbnails, and the full file only when you pick it, and keeps them in a cache on your phone. The list and the caches stay on the phone until you clear them, sign out or uninstall the app.

**Photos already on your phone (optional).** If you allow access, the app compares your server's photos with the ones on the phone, so a photo you have in both places shows once and the phone's copy is used. This comparison happens on the phone; nothing about your phone's photos is sent anywhere.

**Android's photo picker.** Android's picker asks the app for your library and shows it to you. A photo or video you pick goes to the app you picked it in, which is your choice.

**The photo keyboard (optional).** When you tap a photo in the keyboard, the app inserts it into the app you're typing in. The keyboard never reads, stores or sends what you type.

**Shizuku or adb.** With your permission, the app uses Shizuku to change one Android setting: the list of apps allowed to add photos to the picker. It changes nothing else, and you can undo it in the app under Details and troubleshooting.

**Notifications and local network access.** Notifications tell you when the app needs you, for example after an update. Local network access lets the app reach a server on your home network.

## Sharing

The app shares no data with anyone. Data moves only between your phone and your own server, and to the apps you choose when you pick a photo.

If you use **Share for a bug report**, the app builds a text with diagnostics and recent log lines, masking your server's address and user name, and hands it to the app you choose. Nothing is sent unless you send it.

## Security

Connections to servers outside your local network use HTTPS. Plain HTTP is allowed only to addresses on your local network. Certificates you have installed on the phone are trusted too, for self-hosted servers with their own certificate authority. The app's data is excluded from Android's cloud backups and device transfers.

## Children

The app is not directed at children.

## Changes

Any change to this policy is published in this file, with a new date. Its full history is on GitHub.

## Contact

Questions about privacy, or anything else: [open an issue on GitHub](https://github.com/keithvassallomt/nc-media-provider/issues).
