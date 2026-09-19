package com.muktimandiri.timesheet;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

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
        })
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
