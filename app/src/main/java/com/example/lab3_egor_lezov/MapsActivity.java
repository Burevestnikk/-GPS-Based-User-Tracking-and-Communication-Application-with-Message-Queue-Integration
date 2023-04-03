package com.example.lab3_egor_lezov;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, LocationListener {

    private static GoogleMap mMap;
    private LocationManager locationManager;
    private Location myLocation;
    public boolean isMapReady = false;
    public CloudAMQP cloudAMQP;

    public static Handler UIHandler = new Handler(Looper.getMainLooper());

    public static SupportMapFragment mapFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this);
            myLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }

        cloudAMQP = new CloudAMQP();

        mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        isMapReady = true;
        cloudAMQP.setupConnection();
        for (String username : deviceLocations.keySet()) {
            LatLng position = deviceLocations.get(username);
            MarkerOptions markerOptions = new MarkerOptions().position(position).title(username);
            Marker marker = mMap.addMarker(markerOptions);
            deviceMarkers.put(username, marker);
        }
        if (myLocation != null) {
            LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());
            myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));
            mMap.moveCamera(CameraUpdateFactory.newLatLng(myPos));
        }
    }

    private Map<String, LatLng> deviceLocations = new HashMap<>();
    private HashMap<String, Marker> deviceMarkers = new HashMap<String, Marker>();

    private Marker myMarker;


    public void onLocationChanged(Location location) {
        myLocation = location;
        LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());

        if (myMarker == null) {
            myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));
        } else {
            myMarker.setPosition(myPos);
        }

        sendUserLocation(cloudAMQP.userName, myLocation.getLatitude(), myLocation.getLongitude());

        for (String username : userPositions.keySet()) {
            if (!username.equals(cloudAMQP.userName)) {
                LatLng position = userPositions.get(username);
                Marker marker = markers.get(username);
                if (marker != null) {
                    marker.setPosition(position);
                } else {
                    float hue = CloudAMQP.getHueForUser(username);
                    MarkerOptions markerOptions = new MarkerOptions().position(position).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                    Marker userMarker = mMap.addMarker(markerOptions);
                    markers.put(username, userMarker);
                }
            }
        }

        for (String username : markers.keySet()) {
            if (!username.equals(cloudAMQP.userName) && !userPositions.containsKey(username)) {
                markers.get(username).remove();
                markers.remove(username);
            }
        }
    }

    private void updateDevicePositions() {
        LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());
        if (myMarker != null) {
            myMarker.remove();
        }
        myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));

        for (String username : userPositions.keySet()) {
            if (!username.equals(cloudAMQP.userName)) {
                LatLng position = userPositions.get(username);
                float hue = CloudAMQP.getHueForUser(username);
                MarkerOptions markerOptions = new MarkerOptions().position(position).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                Marker userMarker = mMap.addMarker(markerOptions);
                markers.put(username, userMarker);
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    public static Map<String, LatLng> userPositions = new HashMap<>();

    private static Map<String, Marker> markers = new HashMap<>();

    public static void consumeUserLocations(JSONObject json) {
        UIHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println(json.toString());
                    String userName = (String) json.get("userName");
                    double latitude = json.getDouble("latitude");
                    double longitude = json.getDouble("longitude");

                    LatLng userPos = new LatLng(latitude, longitude);
                    if (userPositions.containsKey(userName)) {
                        LatLng oldPos = userPositions.get(userName);
                        if (!oldPos.equals(userPos)) {
                            userPositions.put(userName, userPos);
                            refreshMarkers();
                        }
                    } else {
                        userPositions.put(userName, userPos);
                        refreshMarkers();
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private static void refreshMarkers() {
        mapFragment.getActivity().runOnUiThread(() -> {
            Set<String> markersToRemove = new HashSet<>(markers.keySet());
            for (Map.Entry<String, LatLng> entry : userPositions.entrySet()) {
                String username = entry.getKey();
                LatLng userPos = entry.getValue();
                Marker marker = markers.get(username);
                if (marker != null) {
                    marker.setPosition(userPos);
                    markersToRemove.remove(username);
                } else {
                    float hue = CloudAMQP.getHueForUser(username);
                    MarkerOptions markerOptions = new MarkerOptions().position(userPos).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                    Marker userMarker = mMap.addMarker(markerOptions);
                    markers.put(username, userMarker);
                }
            }
            for (String username : markersToRemove) {
                Marker marker = markers.get(username);
                marker.remove();
                markers.remove(username);
            }
        });
    }

    private void sendUserLocation(String userName, final double latitude, final double longitude) {
        JSONObject json = new JSONObject();
        try {
            json.put("userName", userName);
            json.put("latitude", latitude);
            json.put("longitude", longitude);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        cloudAMQP.publishToExchange(json);
    }
}