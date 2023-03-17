package com.example.lab3_egor_lezov;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

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
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.ShutdownSignalException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

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
    Set<String> visibleMarkers = new HashSet<>();

    private final static int UPDATE_INTERVAL = 5000; // milliseconds
    private Handler mHandler = new Handler();
    private Runnable mUpdateRunnable;

    public void onLocationChanged(Location location) {
        myLocation = location;
        LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());

        // Remove the old marker from the map
        // Add or update the user's marker on the map
        if (myMarker == null) {
            myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));
        } else {
            myMarker.setPosition(myPos);
        }

        // Send the user's location to the server
        sendUserLocation(Build.SERIAL, myLocation.getLatitude(), myLocation.getLongitude());

        // Update the position of other devices on the map
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

        // Remove markers of other devices not visible on the current map
        for (String username : markers.keySet()) {
            if (!username.equals(Build.SERIAL) && !userPositions.containsKey(username)) {
                markers.get(username).remove();
                markers.remove(username);
            }
        }

        // Periodically update the positions of all devices on the map
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
        // Remove all markers from the map
        // mMap.clear();

        // Add the current user's marker
        LatLng myPos = new LatLng(myLocation.getLatitude(), myLocation.getLongitude());
        myMarker = mMap.addMarker(new MarkerOptions().position(myPos).title("My Position"));

        // Add markers for all other devices
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



    public void updateDeviceLocation(String username, double latitude, double longitude) {
        LatLng position = new LatLng(latitude, longitude);
        deviceLocations.put(username, position);
        updateMarker(username, position);
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

    private Set<String> deviceIds = new HashSet<>();
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
                                marker.remove(); // Remove the old marker
                            }
                            // Add new marker to map with updated position
                            float hue = getHueForUser(username);
                            MarkerOptions markerOptions = new MarkerOptions().position(userPos).title(username).icon(BitmapDescriptorFactory.defaultMarker(hue));
                            Marker userMarker = mMap.addMarker(markerOptions);
                            markers.put(username, userMarker);
                        });
                    }
                } else {
                    // Add new marker to map
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




    private class LocationWebSocketEndpoint extends Endpoint {

        private Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            sessions.add(session);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            sessions.remove(session);
        }

        @Override
        public void onError(Session session, Throwable throwable) {
            throwable.printStackTrace();
        }

        public void onMessage(Session session, String message) {
            try {
                JSONObject json = new JSONObject(message);
                String username = json.getString("username");
                double latitude = json.getDouble("latitude");
                double longitude = json.getDouble("longitude");
                LatLng latLng = new LatLng(latitude, longitude);

                if (username.equals(Build.SERIAL)) {
                    // Update the current device's location
                    myLocation = new Location("");
                    myLocation.setLatitude(latitude);
                    myLocation.setLongitude(longitude);
                    updateMyMarker(latLng);
                    sendUserLocation(username, latitude, longitude);
                } else {
                    // Update other devices' locations
                    userPositions.put(username, latLng);
                    updateMarkerForUser(username, latLng);
                }

            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        private void updateMyMarker(LatLng position) {
            if (myMarker == null) {
                myMarker = mMap.addMarker(new MarkerOptions().position(position).title("My Position"));
            } else {
                myMarker.setPosition(position);
            }
        }

        private void updateMarkerForUser(String username, LatLng position) {
            Marker marker = markers.get(username);
            if (marker != null) {
                marker.setPosition(position);
            } else {
                float hue = getHueForUser(username);
                MarkerOptions markerOptions = new MarkerOptions()
                        .position(position)
                        .title(username)
                        .icon(BitmapDescriptorFactory.defaultMarker(hue));
                Marker userMarker = mMap.addMarker(markerOptions);
                markers.put(username, userMarker);
            }
        }
    }

    private void updateMarker(String username, LatLng position) {
        if (isMapReady) {
            Marker marker = deviceMarkers.get(username);
            if (marker == null) {
                MarkerOptions markerOptions = new MarkerOptions().position(position).title(username);
                marker = mMap.addMarker(markerOptions);
                deviceMarkers.put(username, marker);
            } else {
                marker.setPosition(position);
            }
        }
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

    private int getIconForUser(String username) {
        // You can implement a custom logic here to assign a different icon to each user
        // For example, you can use a switch statement to return a different icon for each username
        return R.drawable.user_icon;
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

