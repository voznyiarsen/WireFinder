# WireFinder

Android app that detects known Wi-Fi networks. It loads a CSV of known networks
(SSID, BSSID/MAC, password — optionally encrypted with a password), continuously
scans nearby Wi-Fi, and alerts the user when a known network is nearby.

## Tech Stack

- Kotlin, single-module Android app (no Compose, classic Views + ViewBinding)
- Material 3 dark theme with dynamic colors (OLED black)
- Hilt for DI, Room for storage, DataStore Preferences for settings
- Foreground service (location type) + WorkManager for background scanning
- AES-GCM encryption for password-protected CSV files (custom `WFC1` header)

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

SDK at `/home/tsuchinoko/android/Sdk`, compileSdk 36, minSdk 33, targetSdk 36.

## Project Structure

- `data/` — Room entities and DAO (`APNetwork`, `APNetworkDao`)
- `util/` — `EncryptionService` (CSV encryption), `CsvParser`, `NetworkMatcher`,
  `PreferencesManager` (DataStore), `QrCode` (ZXing WIFI: format), `ErrorDialog`
- `ui/main/` — main screen: detected networks list (`NetworkAdapter`),
  pull-to-refresh manual Wi-Fi scan (`MainViewModel.manualScan()`), tap to
  connect via `WifiNetworkSpecifier`, QR sharing per row
- `ui/settings/` — settings screen (CSV selection, scan interval, password change)
- `ui/crash/` — crash log screen (separate `:crash` process)
- `service/` — `WifiScanForegroundService` (periodic scanning + notifications)

## Behavior Notes

- Main list shows only APs detected within 2x the scan interval window
- Connecting to an AP uses `ConnectivityManager.requestNetwork()` (a system
  prompt appears; `registerNetworkCallback` would silently not prompt)
- Manual scan errors are verbose and include device diagnostics
  (permissions, location services state, cached results)

## Conventions

- No code comments
- Scan/connect logs use log tag `WireFinderScan`
- Verify changes with `./gradlew assembleDebug`
