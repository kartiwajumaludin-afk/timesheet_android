# TimeSheet Karyawan — APK (Capacitor)

Aplikasi Android untuk Clock In / Clock Out. WebView memuat `https://timesheet.elivereport.com/mobile`
(kode web ada di repo `timesheet`); repo ini hanya pembungkus native + plugin.

- `appId`: `com.muktimandiri.timesheet`
- Plugin native custom `TsDevice` (`android/app/src/main/java/com/muktimandiri/timesheet/TsDevicePlugin.java`):
  lokasi + flag mock location (Fake GPS) dan deteksi aplikasi Fake GPS terpasang.
- `@capacitor/push-notifications` (FCM): pengingat Clock Out 18:00-22:00 dikirim server. Butuh `android/app/google-services.json` (Firebase project `tracking-mukti`, app `com.muktimandiri.timesheet`) - berkas ini TIDAK masuk git.
- Izin: Kamera, Lokasi, Notifikasi, Internet.

## Build
```
npm install
npx cap sync android
cd android && gradlew assembleDebug
```
APK: `android/app/build/outputs/apk/debug/app-debug.apk` (hapus APK lama sebelum build baru).
