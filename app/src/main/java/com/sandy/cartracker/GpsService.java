package com.sandy.cartracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class GpsService extends Service {
    private static final String TAG = "GpsService";
    private static final String UPLOAD_URL = "https://i.jatus.top/encoreupload";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("gps_channel", "GPS Upload", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NotificationManager.class)).createNotificationChannel(channel);
            Notification n = new Notification.Builder(this, "gps_channel")
                    .setContentTitle("EncorePosUps: Uploading GPS")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .build();
            startForeground(1, n);
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        LocationRequest request = LocationRequest.create();
        request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        request.setInterval(10000); // 10 seconds
        request.setFastestInterval(5000);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location loc : locationResult.getLocations()) {
                    double lat = loc.getLatitude();
                    double lon = loc.getLongitude();
                    uploadLocation(lat, lon);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException se) {
            Log.e(TAG, "Location permission missing", se);
        }
    }

    private void uploadLocation(double lat, double lon) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPLOAD_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                JSONObject json = new JSONObject();
                json.put("lat", lat);
                json.put("lon", lon);
                json.put("ts", System.currentTimeMillis());
                byte[] out = json.toString().getBytes();
                conn.setFixedLengthStreamingMode(out.length);
                conn.connect();
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
                int code = conn.getResponseCode();
                Log.i(TAG, "Uploaded, code=" + code);
            } catch (Exception e) {
                Log.e(TAG, "Upload failed", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
