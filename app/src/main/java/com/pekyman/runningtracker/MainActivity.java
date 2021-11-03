package com.pekyman.runningtracker;

import android.Manifest;
import android.app.Dialog;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private Location lastKnownLocation;

    private static final int MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 2;
    GoogleMap mGoogleMap;
    MapFragment mapFragment;

    private LocationCallback locationCallback;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationRequest locationRequest;
    private LocationSettingsRequest.Builder settingsRequestBuilder;
    private SettingsClient settingsClient;

    private final int REQUEST_CHECK_SETTINGS = 1;

    static final PolylineOptions LINE_OPTIONS = new PolylineOptions().color(Color.parseColor("#E94335")).width(13f);
    ArrayList<Polyline> routes = new ArrayList<>();
    ArrayList<LatLng> routePoints = null;


    boolean isRunning = false;
    int googlePlayServicesPrompt = 0;
    boolean settingsChangeRequested = false;
    boolean permissionRequested = false;

    boolean uiEnabled = true;
    boolean isGooglePlayServicesAvailable;

    Button startPause;
    MenuItem targetButton;
    MenuItem clearButton;

    TextView distanceDisplay;
    TextView durationDisplay;

    TextView serviceInfoText;

    Marker marker;
    Bitmap markerBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Objects.requireNonNull(getSupportActionBar()).setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayShowCustomEnabled(true);
        getSupportActionBar().setCustomView(R.layout.custom_title);


        checkGooglePlayServices();

        startPause = findViewById(R.id.start_pause_button);
        clearButton = findViewById(R.id.clear_button);
        targetButton = findViewById(R.id.target_button);
        serviceInfoText = findViewById(R.id.text_service_info);

        distanceDisplay = findViewById(R.id.text_distance);
        durationDisplay = findViewById(R.id.text_duration);

        mapFragment = (MapFragment) getFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        BitmapDrawable bitmapDrawable = (BitmapDrawable) getResources().getDrawable(R.drawable.man_running);
        Bitmap bitmap = bitmapDrawable.getBitmap();

        int width = 100;
        int height = 100;
        markerBitmap = Bitmap.createScaledBitmap(bitmap, width, height, false);

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setSmallestDisplacement(1)
                .setInterval(5000)
                .setFastestInterval(1000);

        settingsRequestBuilder = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest);

        settingsClient = LocationServices.getSettingsClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                super.onLocationResult(locationResult);
                for (Location location : locationResult.getLocations()) {
                    if (!cameraZoomed) {
                        zoomToUserLocation(location);
                    } else {
                        if (location.getAccuracy() > 30)
                            return;

                        drawMarker(location);

                        if (isRunning) {
                            drawRoute(location);
                            updateDistance(location);
                        }
                    }
                    lastKnownLocation = location;
                }
            }

            @Override
            public void onLocationAvailability(@NonNull LocationAvailability locationAvailability) {
                super.onLocationAvailability(locationAvailability);

                if (locationAvailability.isLocationAvailable()) {
                    setUiEnabled(true, null);

                } else {
                    setUiEnabled(false, getString(R.string.error_message));
                }
            }
        };

        startPause.setOnClickListener(v -> {
            isRunning = !isRunning;

            if (!isRunning) {
                lastRoutePoints = null;
                pauseTimer();
            } else {
                drawRoute(lastKnownLocation);
                startTimer();
            }
        });

        if (savedInstanceState != null) {
            isRunning = savedInstanceState.getBoolean("IS_RUNNING");
            cameraZoomed = savedInstanceState.getBoolean("CAMERA_ZOOMED");
            duration = savedInstanceState.getInt("DURATION");
            distance = savedInstanceState.getInt("DISTANCE");
            lastKnownLocation = savedInstanceState.getParcelable("LAST_KNOWN_LOCATION");
            lastRoutePoints = savedInstanceState.getParcelableArrayList("LAST_ROUTE_POINTS");

            durationDisplay.setText(getDurationFromSeconds(duration));
            distanceDisplay.setText(String.valueOf(distance));

            routePoints = savedInstanceState.getParcelableArrayList("ROUTES_POINTS");
        }

    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mGoogleMap = googleMap;
        mGoogleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);

        if (routePoints != null) {
            List<LatLng> linePoints = new ArrayList<>();

            for (int i = 0; i < routePoints.size(); i++) {

                if (routePoints.get(i) != null) {
                    linePoints.add(routePoints.get(i));
                } else {
                    Polyline polyline = googleMap.addPolyline(LINE_OPTIONS);
                    polyline.setPoints(linePoints);

                    routes.add(polyline);
                    linePoints = new ArrayList<>();
                }
            }
        }

        if (isRunning) {
            startTimer();
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_bar_menu, menu);

        targetButton = menu.findItem(R.id.target_button);
        clearButton = menu.findItem(R.id.clear_button);

        targetButton.setEnabled(true);
        clearButton.setEnabled(true);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.clear_button) {
            clearAllRoutes();
            resetDistance();
            resetTimer();

            return true;
        } else if (item.getItemId() == R.id.target_button) {
            reposition();

            return true;
        } else if (item.getItemId() == R.id.action_settings) {
            Toast.makeText(this, "Coming soon.", Toast.LENGTH_SHORT).show();
            return true;
        } else if (item.getItemId() == R.id.action_info) {
            Toast.makeText(this, "Coming soon.", Toast.LENGTH_SHORT).show();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    // Disable/Enable UI components and set service info message if it is disabled
    private void setUiEnabled(boolean enabled, String message) {
        uiEnabled = enabled;

        startPause.setEnabled(enabled);
        if (targetButton != null) {
            targetButton.setEnabled(enabled);
        }
        if (clearButton != null) {
            clearButton.setEnabled(enabled);
        }

        if (message == null) {
            serviceInfoText.setVisibility(View.GONE);
            serviceInfoText.setText("");
        } else {
            serviceInfoText.setVisibility(View.VISIBLE);
            serviceInfoText.setText(message);
        }
    }

    private void checkGooglePlayServices() {
        GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
        int status = googleApiAvailability.isGooglePlayServicesAvailable(this);
        if (googlePlayServicesPrompt == 1) {
            if (status == ConnectionResult.SUCCESS) {
                isGooglePlayServicesAvailable = true;
                setUiEnabled(true, null);
            } else {
                isGooglePlayServicesAvailable = false;
                setUiEnabled(false, "You must update Google Play Services.");
            }
            return;
        }
        if (status != ConnectionResult.SUCCESS) {
            if (googleApiAvailability.isUserResolvableError(status)) {
                Dialog dialog = googleApiAvailability.getErrorDialog(this, status, 1);
                if (dialog != null) {
                    dialog.setCancelable(false);
                    dialog.show();
                }
                isGooglePlayServicesAvailable = false;
                googlePlayServicesPrompt = 1;
            } else {
                setUiEnabled(false, "Device doesn't support Google Play Services.");
                isGooglePlayServicesAvailable = false;
            }
        } else {
            isGooglePlayServicesAvailable = true;
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isGooglePlayServicesAvailable) {
            checkGooglePlayServices();
        }
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (!isGooglePlayServicesAvailable) {
            checkGooglePlayServices();
            return;
        }

        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            setUiEnabled(true, null);

            Task<LocationSettingsResponse> task = settingsClient.checkLocationSettings(settingsRequestBuilder.build());

            task.addOnSuccessListener(this, new OnSuccessListener<LocationSettingsResponse>() {
                @Override
                public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                    setUiEnabled(true, null);
                    getLastKnownLocation();
                }
            }).addOnFailureListener(this, new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    if (e instanceof ResolvableApiException) {
                        setUiEnabled(false, "Please, enable Location in settings.");

                        try {
                            if (!settingsChangeRequested) {
                                ResolvableApiException resolvableApiException = (ResolvableApiException) e;
                                resolvableApiException.startResolutionForResult(MainActivity.this, REQUEST_CHECK_SETTINGS);
                                settingsChangeRequested = true;
                            } else {
                                setUiEnabled(true, "Please, enable Location in settings.");
                            }
                        } catch (IntentSender.SendIntentException ignored) {

                        }
                    }
                }
            });

            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null);
            setUiEnabled(true, null);

        } else {
            setUiEnabled(false, getString(R.string.app_name) + " doesn't have permission for using location data.");

            if (!permissionRequested) {
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
            }
            permissionRequested = true;
        }
    }

    private void getLastKnownLocation() throws SecurityException{
        fusedLocationProviderClient.getLastLocation().addOnSuccessListener(this, new OnSuccessListener<Location>() {
            @Override
            public void onSuccess(Location location) {
                if (location != null) {
                    lastKnownLocation = location;

                    zoomToUserLocation(location);
                    drawMarker(location);
                }
            }
        });
    }

    private boolean cameraZoomed = false;

    private void zoomToUserLocation(Location location) {
        LatLng firstLocation = new LatLng(location.getLatitude(), location.getLongitude());
        CameraPosition cameraPosition = new CameraPosition.Builder()
                .target(firstLocation)
                .zoom(17)
                .build();

        mGoogleMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000, null);
        cameraZoomed = true;
    }

    private void drawMarker(Location location) {
        LatLng position = new LatLng(location.getLatitude(), location.getLongitude());

        if (marker == null) {
            marker = mGoogleMap.addMarker(new MarkerOptions()
                    .position(position)
                    .title("Your position")
                    .icon(BitmapDescriptorFactory.fromBitmap(markerBitmap)));
        } else {
            marker.setPosition(position);
        }
    }

    ArrayList<LatLng> lastRoutePoints;

    private void drawRoute(Location location) {
        LatLng newLocation = new LatLng(location.getLatitude(), location.getLongitude());

        if (lastRoutePoints == null) {
            lastRoutePoints = new ArrayList<>();
            lastRoutePoints.add(newLocation);

            routes.add(mGoogleMap.addPolyline(LINE_OPTIONS));
        } else {
            if (!lastRoutePoints.contains(newLocation)) {
                lastRoutePoints.add(newLocation);
            }
        }
        routes.get(routes.size() - 1).setPoints(lastRoutePoints);
    }

    public void clearAllRoutes() {
        for (int i = 0; i < routes.size(); i++) {
            routes.get(i).remove();
        }

        routes = new ArrayList<>();

        lastRoutePoints = null;

        if (isRunning) {
            drawRoute(lastKnownLocation);
        }
    }

    private int distance;
    private int duration;
    private Timer timer;

    private void updateDistance(Location location) {
        float[] results = new float[1];
        Location.distanceBetween(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(),
                location.getLatitude(), location.getLongitude(), results);

        distance += results[0];
        distanceDisplay.setText(String.valueOf(distance));

    }

    public void resetDistance() {
        distance = 0;
        distanceDisplay.setText(String.valueOf(distance));
    }

    private void startTimer() {
        timer = new Timer();

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        duration++;
                        durationDisplay.setText(getDurationFromSeconds(duration));
                    }
                });
            }
        }, 0, 1000);
    }

    private void pauseTimer() {
        if (timer != null) {
            timer.cancel();
        }
    }

    private void resetTimer() {
        duration = 0;
        durationDisplay.setText(getString(R.string.duration));
    }

    private String getDurationFromSeconds(int seconds) {
        int h = seconds / 3600;
        int m = seconds % 3600 / 60;
        int s = seconds % 60;

        String secondsString = String.valueOf(s);
        String minutesString = String.valueOf(m);
        String hoursString = String.valueOf(h);

        secondsString = (secondsString.length() == 1) ? "0" + secondsString : secondsString;
        minutesString = (minutesString.length() == 1) ? "0" + minutesString : minutesString;
        hoursString = (hoursString.length() == 1) ? "0" + hoursString : hoursString;

        return String.format("%s:%s:%s", hoursString, minutesString, secondsString);
    }

    private void reposition() {
        if (lastKnownLocation == null)
            return;

        LatLng location = new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
        mGoogleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 17));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean("IS_RUNNING", isRunning);
        outState.putBoolean("CAMERA_ZOOMED", cameraZoomed);
        outState.putInt("DURATION", duration);
        outState.putInt("DISTANCE", distance);
        outState.putParcelable("MARKER", (mGoogleMap != null) ? mGoogleMap.getCameraPosition() : null);
        outState.putParcelableArrayList("LAST_ROUTE_POINTS", lastRoutePoints);
        outState.putParcelable("LAST_KNOWN_LOCATION", lastKnownLocation);

        ArrayList<LatLng> routesPoints = new ArrayList<>();

        for (int i = 0; i < routes.size(); i++) {
            routesPoints.addAll(routes.get(i).getPoints());
            routesPoints.add(null);
        }

        outState.putParcelableArrayList("ROUTES_POINTS", routesPoints);
    }

    private void stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback);
    }
}