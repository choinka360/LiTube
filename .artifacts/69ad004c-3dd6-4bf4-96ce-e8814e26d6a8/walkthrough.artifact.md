# Walkthrough - Fix IllegalStateException

I have fixed the `IllegalStateException` that was causing the app to crash during startup.

## Changes Made

### Browser Component

- **Modified [TabManager.java](file:///C:/Users/Panigalski/Documents/GitHub/LiTube/app/src/main/java/com/hhst/youtubelite/browser/TabManager.java)**:
    - Updated `commitAndRun` to use `ft.commitAllowingStateLoss()`.
    - Updated the mini-player suspension logic in `enterMiniPlayer` to use `ft.commitAllowingStateLoss()`.

## Verification Results

### Manual Verification
- Deployed the app to the device (RFCW11JGJPE).
- The app successfully launched and displayed the WebView without crashing.
- Verified the fix by checking that no `IllegalStateException: Can not perform this action after onSaveInstanceState` was thrown in the logs.

![App running successfully after fix](file:///C:/Users/Panigalski/AppData/Local/Google/AndroidStudio2026.1.3/projects/litube.371d93f1/.artifacts/69ad004c-3dd6-4bf4-96ce-e8814e26d6a8/screenshot_success.png)
