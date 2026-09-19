package com.muktimandiri.timesheet;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plugin native TimeSheet - dipanggil dari JS (Pages/Mobile/App.jsx):
 *   Capacitor.Plugins.TsDevice.getLocation()        -> { latitude, longitude, accuracy, isMock, provider }
 *   Capacitor.Plugins.TsDevice.checkFakeGpsApps()   -> { found, packages: [...] }
 *
 * isMock = flag "lokasi palsu" dari OS Android (Location.isMock / isFromMockProvider) -
 * dikirim ke server yang menolak Clock In/Out kalau toggle "Scanning Fake GPS" aktif.
 */
@CapacitorPlugin(
    name = "TsDevice",
    permissions = {
        @Permission(alias = "location", strings = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }),
        @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class TsDevicePlugin extends Plugin {

    private static final long GPS_TIMEOUT_MS = 15000;
    private static final long NET_TIMEOUT_MS = 8000;

    /** Paket aplikasi Fake GPS / mock location yang umum dipakai. */
    private static final Set<String> KNOWN_FAKE_GPS = new HashSet<>(Arrays.asList(
        "com.lexa.fakegps",
        "com.incorporateapps.fakegps.fre",
        "com.incorporateapps.fakegps",
        "com.fakegps.mock",
        "com.blogspot.newapphorizons.fakegps",
        "ru.gavrikov.mocklocations",
        "com.theappninjas.fakegpsjoystick",
        "com.evezzon.fakegps",
        "com.gsmartstudio.fakegps",
        "com.rosteam.gpsemulator",
        "com.divi.fakegps",
        "com.flygps",
        "com.hola.gpsfaker",
        "com.tsng.hidemyapplist",
        "org.hola.gpslocation",
        "com.usefullapps.fakegpslocationpro",
        "com.fake.gps.location",
        "com.mockgps.locationchanger",
        "com.pe.fakelocation",
        "de.robv.android.xposed.installer",
        "com.gpsjoystick.fakegps"
    ));

    // ── Lokasi ───────────────────────────────────────────────────────

    @PluginMethod
    public void getLocation(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "locationPermsCallback");
            return;
        }
        fetchLocation(call);
    }

    @PermissionCallback
    private void locationPermsCallback(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            fetchLocation(call);
        } else {
            call.reject("Izin lokasi ditolak. Aktifkan izin lokasi untuk aplikasi ini di Pengaturan HP.");
        }
    }

    private void fetchLocation(PluginCall call) {
        LocationManager lm = (LocationManager) getContext().getSystemService(android.content.Context.LOCATION_SERVICE);
        if (lm == null) {
            call.reject("Layanan lokasi tidak tersedia di perangkat ini.");
            return;
        }

        boolean gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!gps && !net) {
            call.reject("Lokasi/GPS HP sedang mati. Aktifkan Lokasi lalu coba lagi.");
            return;
        }

        try {
            // GPS dulu (paling akurat); kalau tidak dapat dalam batas waktu -> jaringan; terakhir lokasi terakhir yang segar.
            if (gps) {
                current(lm, LocationManager.GPS_PROVIDER, GPS_TIMEOUT_MS, loc -> {
                    if (loc != null) { resolve(call, loc); return; }
                    fallbackNetwork(call, lm, net);
                });
            } else {
                fallbackNetwork(call, lm, net);
            }
        } catch (SecurityException e) {
            call.reject("Izin lokasi ditolak. Aktifkan izin lokasi untuk aplikasi ini di Pengaturan HP.");
        }
    }

    private void fallbackNetwork(PluginCall call, LocationManager lm, boolean net) {
        try {
            if (net) {
                current(lm, LocationManager.NETWORK_PROVIDER, NET_TIMEOUT_MS, loc -> {
                    if (loc != null) { resolve(call, loc); return; }
                    lastKnown(call, lm);
                });
            } else {
                lastKnown(call, lm);
            }
        } catch (SecurityException e) {
            call.reject("Izin lokasi ditolak. Aktifkan izin lokasi untuk aplikasi ini di Pengaturan HP.");
        }
    }

    /** Lokasi terakhir yang diketahui, hanya kalau masih segar (< 60 dtk). */
    private void lastKnown(PluginCall call, LocationManager lm) {
        try {
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (System.currentTimeMillis() - l.getTime()) < 60000 && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
            if (best != null) resolve(call, best);
            else call.reject("Gagal membaca posisi GPS. Pastikan GPS aktif, berada di area terbuka, lalu coba lagi.");
        } catch (SecurityException e) {
            call.reject("Izin lokasi ditolak. Aktifkan izin lokasi untuk aplikasi ini di Pengaturan HP.");
        }
    }

    private interface LocCallback { void done(Location loc); }

    private void current(LocationManager lm, String provider, long timeoutMs, LocCallback cb) {
        final CancellationSignal signal = new CancellationSignal();
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};

        Runnable timeout = () -> { if (!finished[0]) signal.cancel(); };
        main.postDelayed(timeout, timeoutMs);

        LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(getContext()), loc -> {
            if (finished[0]) return;
            finished[0] = true;
            main.removeCallbacks(timeout);
            cb.done(loc);
        });
    }

    private void resolve(PluginCall call, Location loc) {
        boolean mock = Build.VERSION.SDK_INT >= 31 ? loc.isMock() : loc.isFromMockProvider();
        JSObject r = new JSObject();
        r.put("latitude", loc.getLatitude());
        r.put("longitude", loc.getLongitude());
        r.put("accuracy", loc.getAccuracy());
        r.put("provider", loc.getProvider());
        r.put("isMock", mock);
        call.resolve(r);
    }

    // ══════════════════════════════════════════════════════════════
    // SETUP IZIN (Kamera, Lokasi, Notifikasi) - dipakai layar "Setup Aplikasi"
    //
    // Semua memakai intent standar Android, jadi berlaku di SEMUA merek HP
    // (Xiaomi, Oppo, Vivo, Samsung, dst): dialog izin sistem, dan kalau sudah
    // "ditolak permanen" -> langsung buka halaman setelannya.
    // ══════════════════════════════════════════════════════════════

    private static final String CHANNEL_ID = "clockout";

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsEnabled() {
        return NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
    }

    /** Status semua izin sekaligus. channel: "ok" | "off" (dimatikan user) | "none" (belum dibuat). */
    @PluginMethod
    public void checkAll(PluginCall call) {
        JSObject r = new JSObject();
        r.put("camera", granted(Manifest.permission.CAMERA));
        r.put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION));
        r.put("notifications", notificationsEnabled());

        String channel = "none";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = nm != null ? nm.getNotificationChannel(CHANNEL_ID) : null;
            if (ch != null) channel = ch.getImportance() == NotificationManager.IMPORTANCE_NONE ? "off" : "ok";
        } else {
            channel = "ok";
        }
        r.put("channel", channel);
        call.resolve(r);
    }

    private void finishRequest(PluginCall call, boolean granted, String permission) {
        JSObject r = new JSObject();
        r.put("granted", granted);
        // Diblokir = ditolak DAN sistem tidak akan menampilkan dialog lagi ("jangan tanya lagi").
        boolean blocked = !granted && (getActivity() == null
            || !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), permission));
        r.put("blocked", blocked);
        call.resolve(r);
    }

    @PluginMethod
    public void requestCamera(PluginCall call) {
        if (granted(Manifest.permission.CAMERA)) { finishRequest(call, true, Manifest.permission.CAMERA); return; }
        requestPermissionForAlias("camera", call, "cameraRequestCallback");
    }

    @PermissionCallback
    private void cameraRequestCallback(PluginCall call) {
        finishRequest(call, granted(Manifest.permission.CAMERA), Manifest.permission.CAMERA);
    }

    @PluginMethod
    public void requestLocation(PluginCall call) {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) { finishRequest(call, true, Manifest.permission.ACCESS_FINE_LOCATION); return; }
        requestPermissionForAlias("location", call, "locationRequestCallback");
    }

    @PermissionCallback
    private void locationRequestCallback(PluginCall call) {
        boolean ok = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        finishRequest(call, ok, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 12 ke bawah: tidak ada izin runtime; yang ada hanya toggle notifikasi aplikasi.
            JSObject r = new JSObject();
            boolean on = notificationsEnabled();
            r.put("granted", on);
            r.put("blocked", !on);
            call.resolve(r);
            return;
        }
        if (granted(Manifest.permission.POST_NOTIFICATIONS)) {
            // Izin ada, tapi toggle notifikasi aplikasi dimatikan user di Setelan -> harus dinyalakan manual.
            JSObject r = new JSObject();
            boolean on = notificationsEnabled();
            r.put("granted", on);
            r.put("blocked", !on);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notifRequestCallback");
    }

    @PermissionCallback
    private void notifRequestCallback(PluginCall call) {
        finishRequest(call, granted(Manifest.permission.POST_NOTIFICATIONS) && notificationsEnabled(), Manifest.permission.POST_NOTIFICATIONS);
    }

    private void startSettings(PluginCall call, Intent primary) {
        try {
            primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(primary);
            call.resolve();
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(fallback);
                call.resolve();
            } catch (Exception e2) {
                call.reject("Gagal membuka Pengaturan: " + e2.getMessage());
            }
        }
    }

    /** Halaman "Info Aplikasi" (Kamera/Lokasi: Izin -> pilih izinnya -> Izinkan). */
    @PluginMethod
    public void openAppSettings(PluginCall call) {
        startSettings(call, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
    }

    /**
     * Halaman Setelan Notifikasi langsung: channel=true -> halaman channel "Pengingat Clock Out"
     * (tinggal toggle ON); channel=false -> halaman notifikasi aplikasi (toggle utama).
     */
    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        boolean channel = Boolean.TRUE.equals(call.getBoolean("channel", false));
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (channel) {
                i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
            } else {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            }
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
        }
        startSettings(call, i);
    }

    // ── Deteksi aplikasi Fake GPS terpasang ──────────────────────────

    @PluginMethod
    public void checkFakeGpsApps(PluginCall call) {
        JSArray found = new JSArray();
        try {
            PackageManager pm = getContext().getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            String self = getContext().getPackageName();

            for (PackageInfo pi : packages) {
                if (pi.packageName.equals(self)) continue;
                boolean isSystem = pi.applicationInfo != null && (pi.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;

                boolean known = KNOWN_FAKE_GPS.contains(pi.packageName);
                // Aplikasi non-sistem yang meminta izin ACCESS_MOCK_LOCATION = kandidat kuat mock location.
                boolean asksMock = false;
                if (!isSystem && pi.requestedPermissions != null) {
                    for (String perm : pi.requestedPermissions) {
                        if ("android.permission.ACCESS_MOCK_LOCATION".equals(perm)) { asksMock = true; break; }
                    }
                }
                if (known || asksMock) found.put(pi.packageName);
            }
        } catch (Exception ignored) {
            // Gagal mendaftar paket -> anggap tidak ada (isMock per-lokasi tetap jadi lapisan utama).
        }

        JSObject r = new JSObject();
        r.put("found", found.length() > 0);
        r.put("packages", found);
        call.resolve(r);
    }
}
