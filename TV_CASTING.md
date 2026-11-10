# LiTube TV and casting

One APK supports the existing phone app and a native Android TV launcher screen. The TV streams YouTube directly; the phone sends playback commands over the local network. This is LiTube-to-LiTube casting, not Google Cast or screen mirroring. Internet access is required on the TV for YouTube.

## Casting, YouTube style (October 2026 redesign)

1. Open **LiTube TV** on the TV once. Casting starts automatically.
2. On the phone, play a video and tap the **Cast** icon in the player's top bar. TVs on the same Wi-Fi are listed, and remembered TVs are listed straight away.
3. Tap the TV. The first time, the TV asks **Allow <phone> to cast?** and shows a 4-digit code. The phone shows the same code. Select **Allow**. No typing is needed.
4. The video continues on the TV from the same position. The phone shows **Playing on <TV>**. The normal player controls control the TV: play/pause, the seek bar, double-tap/gesture seeking, and the media notification. The phone volume keys change the TV volume. A **Casting to <TV>** notification has Pause/Play and Stop casting.
5. While connected, every video opened on the phone plays on the TV.
6. Tap the Cast icon again for volume, play/pause and **Stop casting**. Stopping continues the video on the phone where the TV left off.

The TV advertises a certificate-derived ID over DNS-SD. A remembered TV is therefore found again after its IP address changes. Up to 8 TVs are remembered. The first connection records the TV's certificate. Approval on the TV is required before any credential is issued, and the certificate is pinned from then on. If discovery is blocked (for example on guest or isolated networks), use **Connect with TV code** on the phone. Enter the address and 16-character code shown in **TV > Settings > Link with phone**.

The phone follows the TV with long-polled `/status` requests (`since`/`version`), so changes made on the TV with its remote appear on the phone immediately. All earlier endpoints and response fields are unchanged; the Chrome extension keeps working. Its pairing no longer needs **Pair a phone** first, because the TV shows the Allow prompt by itself.

## Waking the TV and casting from outside the home network

This uses the same approach as the RemoteWakeUp app: signed UDP commands to `pcstation.hopto.org`, which the router forwards to the TV.

- **Waking.** Every time the phone connects to the TV, at home or from outside, it sends the RemoteWakeUp TV helper's signed `TVWAKE`. It goes to the router's UDP 10 (forwarded to the helper on port 9009) and to the TV's home address. The helper must be installed on the TV, as set up by RemoteWakeUp.
- **Casting from outside.** If the phone isn't on Wi-Fi or Ethernet, or the TV doesn't answer on the home network, LiTube sends the clip as a signed UDP command. It goes to the router's UDP 11 and to the TV's home address, and the TV's LiTube listener on UDP 9010 receives it. The TV wakes and plays the clip.
- **Controls from outside.** Play, pause, seek, stop and volume work, and the phone checks the TV's state every 3 seconds. The phone shows "via the internet". The cast sheet labels a remembered TV that isn't on the current network: tap it to wake it and play.
- **Format.** Commands use the TV helper's format: `NAME:<unix ms>[:args]:<HMAC-SHA256>`.
  - They are refused if more than 2 minutes old, once the TV's clock is set, and replays are refused.
  - The TV doesn't reply to anything unsigned.
  - Only playback commands are accepted, never pairing.
- **TV boot.** LiTube's receiver now starts when the TV boots, so casting works without opening LiTube on the TV. Run `adb shell dumpsys deviceidle whitelist +com.hhst.litube` once after installing, as for the helper, so Android doesn't stop it in standby.

### Setup

1. **Router** (Orange Funbox, port forwarding / NAT page): add **UDP**, external port **11**, internal port **9010**, destination **192.168.1.49**. The helper's existing UDP 10 → 9009 rule stays as it is.
2. **Keys.** In the git-ignored root `local.properties`:
   - `litube.tvHelper.secret` is the RemoteWakeUp TV helper's key, from `tv-agent\secret.txt`.
   - `litube.remote.secret` is a 32-hex-character key of your choice. The phone and the TV must have the same build.
   - Optional: `litube.remote.host` (default none: casting from outside is off), `litube.remote.port` (default 11) and `litube.tvHelper.port` (default 10).
   - Builds without these keys, such as the GitHub Actions build, simply leave casting from outside off.
3. Install the same APK on the phone and the TV.

**Keep the APK private.** It contains both keys, so anyone with it can wake the TV and cast to it, the same caveat as the RemoteWakeUp app.

## TV restarts when switching to an HDMI input (fixed November 2026)

The Sony KD-55XF9005 froze and restarted when switching from LiTube to HDMI 3/ARC.
- **What the logs showed:** on 9 and 10 November, the system log stopped while LiTube's player was still releasing the TV chip's hardware video decoder (MediaTek `MtkOmxVdec`) as the TV started the HDMI input. Each time, the watchdog restarted the TV.
- **The fix:** LiTube now frees the decoder as soon as it leaves the screen. Pressing Input pauses it about two seconds before the switch. The video and position are kept, and the video is shown again, paused, when LiTube returns.
- **Verification:** three switches from playing LiTube to HDMI 3/ARC, with no restart.

## Android TV interface

LiTube TV now looks and works like YouTube for Android TV:

- **Navigation rail** on the left: Search, Home, Account and Settings. The rail expands while focused.
- **Home** shows rows of thumbnail cards with a details header for the focused video. Rows: Continue watching, Recommended for you (related to your last video), Trending (if YouTube still offers it), Music, Because you watched…, Gaming, News, Live and Sports. Each row loads on its own; a row that fails is left out.
- **Search** opens a results grid. You can also paste a YouTube link.
- **Full-screen player:**
  - OK pauses and shows the controls.
  - Left/Right skip 10 seconds.
  - Up/Down show the controls; Down goes to **Up next**.
  - Back hides the controls, then leaves the player.
  - Controls: play/pause, start over, next, captions, quality and speed. The seek bar scrubs with the D-pad.
- **Autoplay** plays the first Up next video when one finishes. It can be turned off in Settings.
- **Settings:**
  - Link with phone (address and code)
  - Autoplay
  - Casting on/off
  - Remove linked devices
  - Open when a phone casts (Android 10+)
  - Wake from standby
  - About

## Previous preview notes (v2.2.0)

The notes below describe the earlier form-based phone screen and are kept for history.

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
