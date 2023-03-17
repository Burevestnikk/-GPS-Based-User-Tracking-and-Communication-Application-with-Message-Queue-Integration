package com.example.lab3_egor_lezov;

public class LocationUpdate {
    private String username;
    private double latitude;
    private double longitude;

    public LocationUpdate(String username, double latitude, double longitude) {
        this.username = username;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getUsername() {
        return username;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}
