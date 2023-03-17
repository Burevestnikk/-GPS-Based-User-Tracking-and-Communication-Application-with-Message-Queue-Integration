package com.example.lab3_egor_lezov;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

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
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, LocationListener {

    private GoogleMap mMap;
    private LocationManager locationManager;
    private Location myLocation;
    private ConnectionFactory factory;
    private Channel channel;
    private String queueName = "EgorUTHQueues";
    private boolean isMapReady = false;

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

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        new Thread(() -> {
            boolean isConnected = false;
            while (!isConnected) {
                try {
                    factory = new ConnectionFactory();
                    factory.setUri("amqps://umxpcwev:H4-UVPDm5rZ7EiAs_M0n4z7MBBOejhNo@sparrow.rmq.cloudamqp.com/umxpcwev");
                    Connection connection = factory.newConnection();
                    channel = connection.createChannel();
                    isConnected = true;
                    consumeUserLocations();
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        Thread.sleep(2000); // Wait for 5 seconds before trying to reconnect
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }).start();
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        isMapReady = true;
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

    private final static int UPDATE_INTERVAL = 5000; // milliseconds
    private Handler mHandler = new Handler();
    private Runnable mUpdateRunnable;

    public void onLocationChanged(Location location) {
        myLocation = location;
        LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());

        if (myMarker == null) {
            myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));
        } else {
            myMarker.setPosition(myPos);
        }

        sendUserLocation(Build.SERIAL, myLocation.getLatitude(), myLocation.getLongitude());

        for (String username : userPositions.keySet()) {
            if (!username.equals(Build.SERIAL)) {
                LatLng position = userPositions.get(username);
                Marker marker = markers.get(username);
                if (marker != null) {
                    marker.setPosition(position);
                } else {
                    float hue = getHueForUser(username);
                    MarkerOptions markerOptions = new MarkerOptions().position(position).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                    Marker userMarker = mMap.addMarker(markerOptions);
                    markers.put(username, userMarker);
                }
            }
        }

        for (String username : markers.keySet()) {
            if (!username.equals(Build.SERIAL) && !userPositions.containsKey(username)) {
                markers.get(username).remove();
                markers.remove(username);
            }
        }

        mHandler.removeCallbacks(mUpdateRunnable);
        mUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDevicePositions();
                mHandler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        mHandler.postDelayed(mUpdateRunnable, UPDATE_INTERVAL);
    }

    private void updateDevicePositions() {
        LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());
        myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));

        for (String username : userPositions.keySet()) {
            if (!username.equals(Build.SERIAL)) {
                LatLng position = userPositions.get(username);
                float hue = getHueForUser(username);
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

    private Map<String, LatLng> userPositions = new HashMap<>();

    private Map<String, Marker> markers = new HashMap<>();

    private void consumeUserLocations() throws IOException {
        channel.queueDeclare(queueName, true, false, false, null);
        channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println("Received message: " + message);
            JSONObject json = null;
            String username;
            try {
                json = new JSONObject(message);
                username = json.getString("username");
                double latitude = json.getDouble("latitude");
                double longitude = json.getDouble("longitude");
                LatLng userPos = new LatLng(latitude, longitude);
                if (userPositions.containsKey(username)) {
                    // Update existing marker position
                    LatLng oldPos = userPositions.get(username);
                    if (!oldPos.equals(userPos)) {
                        userPositions.put(username, userPos);
                        runOnUiThread(() -> {
                            Marker marker = markers.get(username);
                            if (marker != null) {
                                marker.remove();
                            }
                            float hue = getHueForUser(username);
                            MarkerOptions markerOptions = new MarkerOptions().position(userPos).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                            Marker userMarker = mMap.addMarker(markerOptions);
                            markers.put(username, userMarker);
                        });
                    }
                } else {
                    userPositions.put(username, userPos);
                    float hue = getHueForUser(username);
                    MarkerOptions markerOptions = new MarkerOptions().position(userPos).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                    runOnUiThread(() -> {
                        if (isMapReady) {
                            Marker userMarker = mMap.addMarker(markerOptions);
                            markers.put(username, userMarker);
                        }
                    });
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }, consumerTag -> {});
    }

    private void sendUserLocation(final String username, final double latitude, final double longitude) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("username", username);
                json.put("latitude", latitude);
                json.put("longitude", longitude);
                channel.basicPublish("", queueName, null, json.toString().getBytes("UTF-8"));
                channel.waitForConfirmsOrDie();
            } catch (AlreadyClosedException e) {
                if (!channel.isOpen()) {
                    try {
                        channel = factory.newConnection().createChannel();
                        channel.queueDeclare(queueName, true, false, false, null);
                    } catch (IOException | TimeoutException ex) {
                        ex.printStackTrace();
                    }
                }
                sendUserLocation(username, latitude, longitude);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private float getHueForUser(String username) {
        int hashCode = username.hashCode();
        float hue = Math.abs(hashCode % 360);
        return hue;
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            channel.close();
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
        }
    }
}

