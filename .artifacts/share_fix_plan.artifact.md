# Resonance Share Fix & QR Deep Link Plan

Fixing the "MISSING_PERMISSION_NEARBY_WIFI_DEVICES" error, enabling QR deep links, and adding a diagnostic connection test.

## User Review Required
- The connection test will be a hidden or semi-visible "Diagnostic" button in Settings or the Share Sheet.

## Proposed Changes

### [Permissions & Manifest]
Ensure all required Nearby Share permissions are declared and requested correctly.

#### [AndroidManifest.xml](file:///home/yu/StudioProjects/resonance/app/src/main/AndroidManifest.xml)
- Add `NEARBY_WIFI_DEVICES` with `android:usesPermissionFlags="neverForLocation"`.
- Add `<intent-filter>` for `resonance://receive` deep links.

### [Deep Link Support]
Allow the app to open when a `resonance://` QR code is scanned.

#### [MainActivity.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/MainActivity.kt)
- Handle incoming deep links in `onNewIntent` and `onCreate`.

### [Nearby Share Fix]
Update the manager to handle Android 13+ permission logic gracefully.

#### [NearbyShareManager.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/data/service/NearbyShareManager.kt)
- Ensure discovery/advertising only starts after permissions are confirmed.

### [Diagnostics & Testing]
Add a way to verify that Nearby Share and QR transfers work.

#### [ShareViewModel.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/presentation/viewmodel/ShareViewModel.kt)
- Add `runDiagnostics()` method to simulate a local transfer.

#### [SettingsScreen.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/presentation/screens/SettingsScreen.kt)
- Add "Resonance Share Test" under the Data or Library section.

## Verification Plan

### Automated Tests
- Build verification: `./gradlew assembleDebug`

### Manual Verification
1. **Permission Check**: Open Share Sheet on an Android 13+ device and ensure the "Nearby Wi-Fi Devices" prompt appears.
2. **Deep Link Check**: Use `adb shell am start -a android.intent.action.VIEW -d "resonance://receive?title=Test" dev.yuwixx.resonance` to verify the app opens the receive sheet.
3. **Diagnostic Test**: Run the in-app diagnostic to verify the internal file server and Nearby SDK are responsive.
