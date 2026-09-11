# Fix IllegalStateException (Commit after onSaveInstanceState)

The app is crashing with an `IllegalStateException` when trying to open the initial tab during startup. This happens because the fragment transaction is committed in a background task (via `View.post`) when the activity might have already saved its state (e.g., if the screen is off or the app is moved to the background immediately).

## Proposed Changes

### Browser Component

#### [MODIFY] [TabManager.java](file:///C:/Users/Panigalski/Documents/GitHub/LiTube/app/src/main/java/com/hhst/youtubelite/browser/TabManager.java)
- Replace `ft.commit()` with `ft.commitAllowingStateLoss()` in `commitAndRun` and `enterMiniPlayer`. This prevents the crash when UI updates occur while the activity is in a saved state. Since these are mostly transient UI updates (opening a tab or toggling mini-player), allowing state loss is a safe and common fix.

## Verification Plan

### Manual Verification
- Deploy the app to the device.
- Verify that the app launches without crashing.
- Try to background the app immediately after launch to ensure no "commit after onSaveInstanceState" occurs.
