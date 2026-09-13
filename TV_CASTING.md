# LiTube v2.2.0 TV Preview

One APK supports the existing phone app and a native Android TV launcher screen. The TV streams YouTube directly; the phone sends playback commands over the local network. This is LiTube-to-LiTube casting, not Google Cast or screen mirroring. Internet access is required on the TV for YouTube.

## Install and use

Install `app/build/outputs/apk/debug/app-debug.apk` on both devices. This preview is debug-signed; it updates the development builds used in this project. A differently signed store/release installation cannot be updated with it without resolving signing first.

1. Open **LiTube TV** from the TV launcher. Wait until the address and pairing code appear.
2. Select **Pair a phone** on the TV.
3. On the phone, open a video, choose **More > Cast to TV**, or share a YouTube link to **Cast with LiTube**.
4. Select **Find TVs on Wi-Fi**, choose the TV, and enter its displayed 16-character code. Manual local IP entry also works; the default port is 8642.
5. Select **Pair with TV**, then approve the phone on the TV.
6. Select **Cast video / wake TV**. Pause, Resume, Stop and Wake TV are available on the phone. After a successful cast, the normal phone player play/pause buttons, gestures and media-notification actions control that same TV video. Opening casting settings or backgrounding the phone does not pause the TV. The top of the casting screen also has Playback position (seek slider), Video quality (Auto or a supported TV track), Subtitles (available languages or Off), and Playback speed (0.25x to 2x). Each menu reads the current TV state; unavailable tracks are not offered. Quality/subtitle changes preserve the other track type and do not restart the clip. Casting automatically fills the TV screen and hides setup information and playback controls. Use the remote to reveal playback controls; Back returns to the search/pairing controls. The TV also supports native search, direct YouTube links and remote playback controls.

The tested Sony TV is at 192.168.1.12. Both devices must be on the same reachable LAN; guest-network isolation can prevent discovery and connections. Pairing is remembered. TV settings can revoke all paired phones.

## Sony KD-55XF9005, Android 9: wake setup

Keep casting enabled. Normal standby wake was verified on this TV using an authenticated phone command: Android reported Asleep before the request and Awake afterward, with LiTube TV reopened.

Enable Sony **Remote Start** in the TV network settings for network standby. Optionally enter the MAC address of the TV's active network adapter on the phone for Wake-on-LAN. Deep standby/Wake-on-LAN behavior has not been verified on this hardware; it depends on the TV firmware and network-standby settings. An unplugged TV cannot be woken. Open LiTube after a TV reboot: this preview does not automatically start its receiver at boot. Disabling casting stops its receiver.

On Android 10+ TVs, background activity opening may require the optional "display over other apps" permission exposed in TV settings. Otherwise leave LiTube open or open the receiver notification. This exception is not needed on the tested Android 9 Sony.

Sony guidance: https://helpguide.sony.net/tv/fgal1/v1/en/060nhm-06-01-06.html

## Implementation and safety

The receiver uses TLS with a device-generated Android Keystore certificate. The physically displayed pairing code pins that certificate; TV approval issues a random per-phone bearer credential. Saved credentials are excluded from Android backup. Requests, worker counts and pairing windows are bounded. Playback commands are idempotent across connection retries. Casting remains a foreground service with a visible disable action.

TV playback reuses the repaired HLS source handling, Media3 1.10.1 and bounded 90–120 second buffering policy from phone playback. Actual buffer duration depends on available memory, stream bitrate and connection speed.

## Verification, 13 September 2026

- Debug app and instrumentation APK builds succeeded; 67 JVM unit tests passed.
- Real Samsung SM-G781B to Sony Android 9 casting: approved pairing, playback, pause holding position, seek to 70 seconds, resume, and continuous progress through 151 seconds passed. Sampled TV video format was 1080p throughout the post-seek interval.
- Normal standby wake from the phone passed after waiting for receiver startup. An initial test attempted sleep before the freshly installed receiver had initialized and was repeated after readiness.
- Real Wi-Fi discovery found the TV from the phone. Both secure transport device tests passed on the final build and cover authenticated requests, rejected credentials, rejected certificate pins and public endpoint rejection.
- Remote controls: live pause/resume, seek to 70 seconds, 1.5x speed, 720p selection and return to Auto passed. A device-side track-selection contract test verified subtitle on/off/re-enable without clearing video overrides. The live test clip exposed no subtitle tracks, so actual caption rendering was not verified in this run.
- TV full-screen layout was inspected. An instrumentation test pressed the actual normal phone player Pause and Play buttons and verified the Sony TV paused and resumed; the shared session test also passed.
- Android lint completed. New TV Media3 opt-in findings and Ethernet-only TV availability warning were fixed. Three pre-existing lint errors remain in extension UI resources (AppCompatResource/UseAppTint); lint is configured by this repository not to abort on errors. New preview UI text is currently English.

Tests exercise the observed failure paths; they do not prove every TV firmware, YouTube clip, extended playback session or deep-standby configuration is bug-free.

Unavailable premieres now show a plain Video unavailable message, including the reported start time, instead of a technical error dialog.

## YouTube suggestions and account connection

The TV menu now shows Suggested by YouTube for the currently loaded video. It preserves YouTube's returned order and offers up to 20 related clips. Open it with Back from full screen; use Refresh suggested videos to retry. These are related-video suggestions, not the personalized Home feed. The suggestions and encrypted-token storage tests passed on the Sony TV.

YouTube account / Sign in opens LiTube's Google device-code sign-in. The code is entered on Google's verification website using your phone. The app requests read-only YouTube access, stores tokens encrypted with Android Keystore, refreshes expired access tokens and supports sign-out/revocation. The LiTube Android TV client is registered in the LiTube project (litube-508420), and the configured build obtained a real Google device sign-in code on the Sony TV. YouTube Data API v3 is enabled and the owner is configured as a test user. End-to-end Google authorization passed on the Sony TV. Reopening the account screen loaded the encrypted saved token and successfully fetched the signed-in channel name from YouTube. The configured APK was installed on both the Sony TV and Samsung phone. Temporary test packages were removed and the phone screen timeout was restored to its original 30 seconds.

The build reads `litube.google.clientId` and `litube.google.clientSecret` from the ignored root `local.properties`. Use a client of type TVs and Limited Input devices in the LiTube Cloud project, with YouTube Data API v3 enabled. Never commit local.properties or downloaded credentials. Testing-mode OAuth clients only allow configured test users and may require periodic reauthorization. Google does not expose the personalized YouTube Home feed through its public Data API, even after sign-in.

Sources: https://developers.google.com/identity/protocols/oauth2/limited-input-device and https://developers.google.com/youtube/v3/docs/activities/list

## TV refresh and interface update, 13 September 2026

Refresh now uses the current video, an entered YouTube link, or the last successfully played clip saved on the TV. With no source clip it shows an explicit Choose a video first dialog. Loading disables duplicate requests, errors explain how to retry, and successful manual refresh focuses and scrolls to the first suggestion. Suggestion requests run separately from search and stale results are discarded.

The TV and account screens use a navy gradient theme with teal/violet focus cards, rounded surfaces, shadows and a subtle animated lift. Fullscreen casting hides the brand and controls.

Before updating the installed app, the separate com.hhst.litube.validation package passed the real Sony TV button test: missing-video feedback, two fresh YouTube refreshes returning 20 items, loading state, and focus on the first result. Screenshots verified clipping of elevated cards. All 67 JVM tests passed. Build the isolated test copy with -PlitubeValidation; omit that property for the normal update APK.
