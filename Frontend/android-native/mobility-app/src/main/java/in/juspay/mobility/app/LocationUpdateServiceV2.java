/*
 *  Copyright 2022-23, Juspay India Pvt Ltd
 *  This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. This program
 *  is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details. You should have received a copy of
 *  the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package in.juspay.mobility.app;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static in.juspay.mobility.common.MobilityCommonBridge.isServiceRunning;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.location.LocationManagerCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.firebase.perf.metrics.AddTrace;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import in.juspay.hypersdk.data.KeyValueStore;
import in.juspay.mobility.app.RemoteConfigs.MobilityRemoteConfigs;
import in.juspay.mobility.common.services.MobilityAPIResponse;
import in.juspay.mobility.common.services.MobilityCallAPI;

/**
 * A foreground service for managing location updates for a driver app.
 * <p>
 * This service is responsible for:
 * 1. Collecting location updates from the device using FusedLocationProvider
 * 2. Batching location updates to optimize network usage and battery consumption
 * 3. Sending location batches to the backend server
 * 4. Ensuring reliable location tracking with heartbeat mechanism
 * 5. Providing mechanisms to handle network failures with location caching
 * 6. Detecting special pickup zones and notifying the UI
 * <p>
 * The service implements several optimizations:
 * - Battery optimization using task-specific wake locks
 * - Network optimization through location batching
 * - Configurable parameters for update intervals and batch sizes
 * - Fallback mechanisms for error cases (network issues, permissions)
 * - Location staleness detection and refresh
 * <p>
 * Configuration is managed through SharedPreferences and can be updated dynamically.
 */
public class LocationUpdateServiceV2 extends Service {
    // Replace the existing log tag constants with these
    private static final String TAG = "LocSvc";
    private static final String TAG_LOCATION = "LocSvc:Location";
    private static final String TAG_BATCH = "LocSvc:Batch";
    private static final String TAG_CACHE = "LocSvc:Cache";
    private static final String TAG_API = "LocSvc:API";
    private static final String TAG_CONFIG = "LocSvc:Config";
    private static final String TAG_ERROR = "LocSvc:Error";
    private static final String TAG_JSON = "LocSvc:JSON";
    private static final String LOCATION_CACHED_BATCH = "LOCATION_CACHED_BATCH";
    private static final String LAST_LOCATION_TIME = "LAST_LOCATION_TIME";
    private static final String LOCATION_REQUEST_INTERVAL = "LOCATION_REQUEST_INTERVAL";
    private static final String LOCATION_UPDATE_INTERVAL = "LOCATION_UPDATE_INTERVAL";
    private static final String LOCATION_BATCH_INTERVAL = "LOCATION_BATCH_INTERVAL";
    private static final String DRIVER_MIN_DISPLACEMENT = "DRIVER_MIN_DISPLACEMENT"; // Updated key
    private static final String LOCATION_BATCH_SIZE = "LOCATION_BATCH_SIZE";
    private static final String LOCATION_SERVICE_VERSION = "LOCATION_SERVICE_VERSION";
    private static final String LOCATION_CACHE_FILE = "location_cache.json";
    private static final String LOCATION_RATE_LIMIT_SECONDS = "LOCATION_RATE_LIMIT_SECONDS"; // New key for rate limiting in seconds

    private static final ArrayList<UpdateTimeCallback> updateTimeCallbacks = new ArrayList<>();
    // Message types for our MessageQueue
    private static final int MSG_LOCATION_UPDATE = 1;
    private static final int MSG_BATCH_PROCESS = 2;
    private static final int MSG_CACHE_FLUSH = 3;
    // Add these variables to track the last location state
    private static final String TAG_TIMER = "LocSvc:Timer";
    private static final String LAST_LOCATION_FETCH_TIME = "LAST_LOCATION_FETCH_TIME";
    private static final String LOCATION_FRESHNESS_THRESHOLD = "LOCATION_FRESHNESS_THRESHOLD";
    // Add these constants for the new settings
    private static final String LOCATION_MAX_BATCH_AGE_KEY = "LOCATION_MAX_BATCH_AGE";
    private static final String LAST_BATCH_SENT_TIME = "LAST_BATCH_SENT_TIME";
    // Add this constant
    private static final String REGISTRATION_TOKEN_KEY = "REGISTERATION_TOKEN";
    // Replace the two separate keys with a single key for JSON config
    private static final String CONFIG_WAKE_LOCK = "location_service_wake_lock_config";
    // Default JSON structure for wake lock config if none exists
    private static final String DEFAULT_WAKE_LOCK_CONFIG = "{\"enabled\":false,\"timeout_ms\":30000}";
    // Notification IDs
    final int notificationServiceId = 15082022;
    final int alertNotificationId = 7102022;
    // Config keys
    private final String LOCATION_UPDATES = "LOCATION_UPDATES";
    private final ReentrantLock batchProcessingLock = new ReentrantLock();
    private final AtomicBoolean isBatchProcessing = new AtomicBoolean(false);
    private final LocalBinder binder = new LocalBinder();
    // Add this class variable to track active location requests
    private final AtomicBoolean isLocationRequestInProgress = new AtomicBoolean(false);
    CancellationTokenSource cancellationTokenSource;
    // Remote configuration
    MobilityRemoteConfigs remoteConfigs;
    // Location components
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private HandlerThread locationHandlerThread;
    // Message queue and batch processing
    private MessageQueue messageQueue;
    private ScheduledExecutorService batchScheduler;
    // Optimization variables
    private PowerManager powerManager;
    private int locationUpdateInterval = 10; // seconds
    private int locationBatchInterval = 10; // seconds
    private float locationMinDisplacement = 25.0f; // meters
    private int locationBatchSize = 20; // How many locations to batch before sending
    // State tracking
    private boolean isLocationUpdating = false;
    private double lastLatitudeValue;
    private double lastLongitudeValue;
    private Context context;
    private String driverRideStatus = "IDLE";
    private String vVariant, merchantID, drMode;
    private String driverId = "empty";
    private boolean isSpecialpickup = false;
    // Connectivity monitoring
    private BroadcastReceiver internetBroadcastReceiver;
    // Timers for periodic updates
    private Timer locationHeartbeatTimer;
    private int locationFreshnessThresholdMinutes = 5; // Default timeout for considering location stale
    private Location lastCachedLocation;
    private int locationRequestInterval = 10; // Default interval in seconds
    // Add these state variables
    private int locationMaxBatchAgeSeconds = 10; // Default max age for a batch before sending regardless of size
    private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;
    private SharedPreferences sharedPrefs;
    // Add these fields
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    // Existing variables ...
    private boolean useWakeLock = false;  // Default to false until config is loaded
    private long wakeLockTimeoutMs = 30000;  // Default 30 seconds timeout
    private long rateLimitTimeInSeconds = 2; // Default rate limiting time in seconds

    /**
     * Registers a callback to receive location update notifications.
     *
     * @param timeUpdateCallback The callback to register
     */
    public static void registerCallback(UpdateTimeCallback timeUpdateCallback) {
        LocationUpdateServiceV2.updateTimeCallbacks.add(timeUpdateCallback);
    }

    /**
     * Unregisters a previously registered location update callback.
     *
     * @param timeUpdateCallback The callback to unregister
     */
    public static void deRegisterCallback(UpdateTimeCallback timeUpdateCallback) {
        LocationUpdateServiceV2.updateTimeCallbacks.remove(timeUpdateCallback);
    }

    /**
     * Provides the binder interface for components binding to this service.
     *
     * @param intent The Intent that was used to bind to this service
     * @return An IBinder through which clients can call on to the service
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Initializes the service when it's first created.
     * <p>
     * This method performs the following initialization steps:
     * 1. Sets up shared preferences and validates registration token
     * 2. Initializes the message queue for location batching
     * 3. Initializes location components
     * 4. Starts as a foreground service with notification
     * 5. Sets up preference change listener
     * 6. Loads configuration
     * 7. Sets up network connectivity monitoring
     * 8. Starts batch scheduler and location updates
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate()");

        context = getApplicationContext();

        // Initialize shared preferences first
        sharedPrefs = getApplicationContext().getSharedPreferences(
                getString(R.string.preference_file_key), MODE_PRIVATE);

        // Initialize Power Manager first
        powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);

        // Check token validity before proceeding
        if (!checkRegistrationTokenExistence()) {
            Log.w(TAG, "Registration token missing, stopping service");
            stopSelf();
            return;
        }

        // Initialize message queue with dedicated looper thread
        initializeMessageQueue();

        // Initialize location components
        initializeLocationComponents();

        // Start as foreground service
        startAsForegroundService();

        // Register preference change listener 
        setupPreferenceChangeListener();

        // Load configuration
        remoteConfigs = new MobilityRemoteConfigs(false, false);
        updateConfigVariables();

        // Initialize internet connectivity receiver
        setupInternetReceiver();

        // Start the batch scheduler
        startBatchScheduler();

        // Restore any cached locations
        messageQueue.restoreFromCache();

        // Start the heartbeat timer
        startLocationHeartbeatTimer();

        Log.i(TAG, "Service initialization complete");
    }

    /**
     * Initializes the message queue system with a dedicated handler thread.
     * The message queue is responsible for handling location data storage and processing.
     */
    private void initializeMessageQueue() {
        HandlerThread messageThread = new HandlerThread("LocationMessageQueue");
        messageThread.start();
        messageQueue = new MessageQueue(messageThread.getLooper());
        Log.d(TAG, "Message queue initialized");
    }

    /**
     * Initializes location-related components including the FusedLocationProviderClient,
     * cancellation token, and dedicated location handler thread.
     */
    private void initializeLocationComponents() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        cancellationTokenSource = new CancellationTokenSource();

        // Create a dedicated thread for location callbacks
        locationHandlerThread = new HandlerThread("LocationCallbackThread");
        locationHandlerThread.start();

        Log.d(TAG, "Location components initialized");
    }

    /**
     * Starts this service as a foreground service with the appropriate notification.
     * Required for reliable operation on modern Android versions.
     */
    private void startAsForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationServiceId, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(notificationServiceId, createNotification());
            }
            Log.d(TAG, "Started as foreground service");
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error starting foreground service", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Sets up network connectivity monitoring using the appropriate API based on Android version.
     * For Android N (API 24) and above, uses NetworkCallback.
     * For older versions, uses BroadcastReceiver with CONNECTIVITY_ACTION.
     * <p>
     * When network connectivity is restored, triggers the batch processing and starts the GRPC service.
     */
    private void setupInternetReceiver() {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Modern implementation using NetworkCallback (Android N and above)
            if (networkCallback == null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull android.net.Network network) {
                        Log.i(TAG, "Network connectivity available");

                        // Execute on main thread to avoid concurrency issues
                        new Handler(Looper.getMainLooper()).post(() -> {
                            messageQueue.triggerBatchProcess();

                            // Start GRPC service if not running
                            if (!isServiceRunning(context, GRPCNotificationService.class.getName())) {
                                Log.i(TAG, "Starting GRPC service");
                                Intent grpcServiceIntent = new Intent(context, GRPCNotificationService.class);
                                context.startService(grpcServiceIntent);
                            }
                        });
                    }
                };

                try {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    Log.d(TAG, "Registered network callback for connectivity monitoring");
                } catch (Exception e) {
                    Log.e(TAG_ERROR, "Failed to register network callback", e);
                }
            }
        } else {
            // Legacy implementation using BroadcastReceiver for older Android versions
            internetBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "Network connectivity changed");
                    if (isNetworkAvailable(context)) {
                        Log.i(TAG, "Network available - triggering batch process");
                        messageQueue.triggerBatchProcess();

                        // Start GRPC service if not running
                        if (isServiceRunning(context, LocationUpdateServiceV2.class.getName()) && !isServiceRunning(context, GRPCNotificationService.class.getName())) {
                            Log.i(TAG, "Starting GRPC service");
                            Intent grpcServiceIntent = new Intent(context, GRPCNotificationService.class);
                            context.startService(grpcServiceIntent);
                        }
                    }
                }
            };

            try {
                context.unregisterReceiver(internetBroadcastReceiver);
            } catch (Exception e) {
                // Ignore - receiver might not be registered
            }

            context.registerReceiver(internetBroadcastReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

            Log.d(TAG, "Registered broadcast receiver for connectivity monitoring (legacy)");
        }
    }

    /**
     * Initializes and starts the scheduled executor for periodic batch processing.
     * The batch processor will run at regular intervals defined by locationBatchInterval.
     */
    private void startBatchScheduler() {
        batchScheduler = Executors.newSingleThreadScheduledExecutor();
        batchScheduler.scheduleWithFixedDelay(
                () -> messageQueue.triggerBatchProcess(),
                locationBatchInterval,
                locationBatchInterval,
                TimeUnit.SECONDS
        );
        Log.d(TAG_BATCH, "Batch scheduler started with interval: " + locationBatchInterval + "s");
    }

    /**
     * Called when the service is started or restarted.
     * Updates configuration, starts location updates if not already running,
     * and ensures the GRPC service is running.
     *
     * @param intent  The Intent supplied to startService
     * @param flags   Additional data about this start request
     * @param startId A unique integer representing this specific request to start
     * @return The return value indicates what semantics the system should use for the service's current started state
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service onStartCommand()");

        // Make sure we're a foreground service
        startAsForegroundService();

        // Load driver ID
        SharedPreferences sharedPrefs = getApplicationContext().getSharedPreferences(
                getString(R.string.preference_file_key), MODE_PRIVATE);
        if (sharedPrefs != null) {
            driverId = sharedPrefs.getString("DRIVER_ID", "empty");
        }

        // Update settings from SharedPreferences
        updateConfigVariables();

        // Start location updates if not already running
        if (!isLocationUpdating) {
            startLocationUpdates();
        }

        // Start GRPC service if not running
        if (!isServiceRunning(context, GRPCNotificationService.class.getName())) {
            Intent grpcServiceIntent = new Intent(context, GRPCNotificationService.class);
            context.startService(grpcServiceIntent);
        }

        // Log health check event if applicable
        logEventForHealthCheck(intent);

        return START_STICKY;
    }

    /**
     * Updates service configuration variables from SharedPreferences.
     * Reads and applies all configurable parameters such as intervals, thresholds, and batch sizes.
     */
    private void updateConfigVariables() {
        try {
            // Update ride status
            driverRideStatus = sharedPrefs.getString("DRIVER_RIDE_STATUS", "IDLE");

            // Update location update interval (seconds)
            String updateIntervalStr = sharedPrefs.getString(LOCATION_UPDATE_INTERVAL, null);
            locationUpdateInterval = updateIntervalStr != null ? Integer.parseInt(updateIntervalStr) : 10;

            // Update batch interval (seconds)
            String batchIntervalStr = sharedPrefs.getString(LOCATION_BATCH_INTERVAL, null);
            locationBatchInterval = batchIntervalStr != null ? Integer.parseInt(batchIntervalStr) : 30;

            // Update minimum displacement (meters)
            String minDisplacementStr = sharedPrefs.getString(DRIVER_MIN_DISPLACEMENT, null); // Updated key
            locationMinDisplacement = minDisplacementStr != null ? Float.parseFloat(minDisplacementStr) : 25.0f; // Updated key

            // Update batch size
            String batchSizeStr = sharedPrefs.getString(LOCATION_BATCH_SIZE, null);
            locationBatchSize = batchSizeStr != null ? Integer.parseInt(batchSizeStr) : 20;

            // Load the freshness threshold for location timeout
            String freshnessThresholdStr = sharedPrefs.getString(LOCATION_FRESHNESS_THRESHOLD, null);
            locationFreshnessThresholdMinutes = freshnessThresholdStr != null ? Integer.parseInt(freshnessThresholdStr) : 5;

            // Load the max batch age before sending
            String maxBatchAgeStr = sharedPrefs.getString(LOCATION_MAX_BATCH_AGE_KEY, null);
            locationMaxBatchAgeSeconds = maxBatchAgeStr != null ? Integer.parseInt(maxBatchAgeStr) : locationMaxBatchAgeSeconds;

            String requestIntervalStr = sharedPrefs.getString(LOCATION_REQUEST_INTERVAL, null);
            locationRequestInterval = requestIntervalStr != null ? Integer.parseInt(requestIntervalStr) : 10;

            loadWakeLockConfig();

            Log.d(TAG_CONFIG, String.format(Locale.US,
                    "Config loaded: locationRequestInterval=%ds, updateInterval=%ds, batchInterval=%ds, minDisplacement=%.1fm, " +
                            "batchSize=%d, freshnessThreshold=%dm, maxBatchAge=%ds",
                    locationRequestInterval, locationUpdateInterval, locationBatchInterval, locationMinDisplacement,
                    locationBatchSize, locationFreshnessThresholdMinutes, locationMaxBatchAgeSeconds));

            // Load the rate limiting time from SharedPreferences (in seconds)
            String rateLimitStr = sharedPrefs.getString(LOCATION_RATE_LIMIT_SECONDS, "2"); // Default to 2 seconds
            rateLimitTimeInSeconds = Long.parseLong(rateLimitStr); // Store in local variable

            Log.d(TAG_CONFIG, "Rate limiting time set to " + rateLimitTimeInSeconds + " seconds");

        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error updating configuration", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Starts the location update request with FusedLocationProvider.
     * Configures the update interval, priority, and other parameters based on current settings.
     * Checks for permissions and whether location services are enabled before starting.
     */
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (isLocationUpdating) {
            Log.d(TAG_LOCATION, "Location updates already running");
            return;
        }

        Log.i(TAG_LOCATION, "Starting location updates");

        // Check if we have location permissions
        if (ActivityCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG_ERROR, "No location permissions");
            return;
        }

        // Check if location is enabled
        if (isLocationDisabled()) {
            Log.e(TAG_ERROR, "Location is disabled");
            updateDriverStatus(false);
            return;
        }

        // Create location request
        LocationRequest locationRequest = createLocationRequest();

        // Create location callback
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                handleLocationResult(locationResult);
            }
        };

        // Request location updates
        try {
            fusedLocationProviderClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    locationHandlerThread.getLooper()
            );
            isLocationUpdating = true;
            Log.i(TAG_LOCATION, "Location updates started successfully");
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Failed to start location updates", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Creates a LocationRequest object based on current configuration.
     * Adjusts priority based on driver status and sets appropriate update intervals and displacement thresholds.
     *
     * @return A configured LocationRequest object
     */
    private LocationRequest createLocationRequest() {
        // Convert seconds to milliseconds
        int intervalMs = locationRequestInterval * 1000;

        // Determine priority based on driver status
        int priority = Utils.getLocationPriority("driver_fused_location_priority");
        Log.d(TAG_CONFIG, String.format(Locale.US,
                "Creating location request: interval=%dms, minDisplacement=%.1fm, priority=%d",
                intervalMs, locationMinDisplacement, priority));

        // Build the location request with both time and distance triggers
        return new LocationRequest.Builder(priority)
                // Time-based updates
                .setIntervalMillis(intervalMs)                     // Target update interval
                .setMinUpdateIntervalMillis(intervalMs / 2)        // Fastest allowed update rate
                .setMaxUpdateDelayMillis(intervalMs * 2L)           // Maximum time without updates

                // Distance-based updates - device will update when moved this far
                .setMinUpdateDistanceMeters(locationMinDisplacement)

                // This ensures we get location updates EITHER when time passes OR when distance changes
                // whichever happens first
                .setWaitForAccurateLocation(driverRideStatus.equals("ON_RIDE"))
                .build();
    }

    /**
     * Stops location updates by removing the location callback from FusedLocationProvider.
     */
    private void stopLocationUpdates() {
        if (fusedLocationProviderClient != null && locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            isLocationUpdating = false;
            Log.i(TAG_LOCATION, "Location updates stopped");
        }
    }

    /**
     * Handles location results received from FusedLocationProvider.
     * For each valid location, updates stored values, logs the location,
     * adds it to the processing queue, and checks for special pickup zones.
     *
     * @param locationResult The location result containing one or more locations
     */
    private void handleLocationResult(LocationResult locationResult) {
        List<Location> locations = locationResult.getLocations();
        if (locations.isEmpty()) {
            Exception e = new Exception("Locations Empty callback");
            FirebaseCrashlytics.getInstance().recordException(e);
            Log.d(TAG_LOCATION, "Received empty location result");
            return;
        }

        Log.d(TAG_LOCATION, "Received " + locations.size() + " locations");

        if (handleDemoMode()) return;

        Collections.sort(locations, (o1, o2) -> (int) (TimeUnit.NANOSECONDS.toMillis(o1.getElapsedRealtimeNanos()) - TimeUnit.NANOSECONDS.toMillis(o2.getElapsedRealtimeNanos())));

        for (Location location : locations) {
            if (location == null) continue;

            // Update last known values
            lastLatitudeValue = location.getLatitude();
            lastLongitudeValue = location.getLongitude();

            // Save as the cached location
            lastCachedLocation = location;

            // Update fetch time when we get a new location
            updateStorage(LAST_LOCATION_FETCH_TIME, String.valueOf(System.currentTimeMillis()));

            // Update storage with location data
            updateLocationStorage(location);

            // Log location data
            logLocationUpdate(location);

            // Create location data object and add to queue
            LocationData locationData = new LocationData(location, "fused_location_provider");
            messageQueue.enqueueLocation(locationData);

            // Check for special pickup zones
            checkNearByPickupZone(location);
        }
    }

    /**
     * Handles demo mode functionality by checking if demo mode is enabled and enqueueing predefined locations.
     *
     * @return true if demo mode is handled, false if demo mode is not enabled
     */
    public boolean handleDemoMode() {
        // Check if demo mode is enabled
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.preference_file_key), MODE_PRIVATE);
        boolean isDemoModeEnabled = Boolean.parseBoolean(sharedPref.getString("IS_DEMOMODE_ENABLED", "false"));

        if (isDemoModeEnabled) {
            // Enqueue predefined locations based on demo mode
            enqueueDemoModeLocations();
            updateStorage(LAST_LOCATION_FETCH_TIME, String.valueOf(System.currentTimeMillis()));
            return true; // Exit early since we are handling demo mode
        } else {
            return false;
        }
    }

    /**
     * Enqueues predefined location data based on the demo mode password.
     * Different passwords correspond to different predefined locations.
     * Updates the last cached location and adds it to the message queue.
     */
    private void enqueueDemoModeLocations() {
        // Define predefined demo locations
        Location demoLocation;
        // Add more predefined locations as needed

        String demoModePassword = sharedPrefs.getString("DEMO_MODE_PASSWORD", "null");

        switch (demoModePassword) {
            case "8917234":
                demoLocation = (createLocation(13.260559676317829, 76.4785809882692));
                break;
            case "9178234":
                demoLocation = (createLocation(13.160550263780683, 76.66727044721313));
                break;
            case "1789234":
                demoLocation = (createLocation(12.522069908884921, 76.89518072273476));
                break;
            case "7891789":
                demoLocation = (createLocation(23.06194031948526, 88.7637073215878));
                break;
            case "7891788":
                demoLocation = (createLocation(24.338294091147212, 88.1949706368274));
                break;
            case "7891567":
                demoLocation = (createLocation(9.869715234892222, 76.37632251438302));
                break;
            case "7891678":
                demoLocation = (createLocation(9.955097514840311, 76.37173322025349));
                break;
            default:
                demoLocation = (createLocation(13.311895563147432, 76.93981481869986));
                break;
        }

        lastCachedLocation = demoLocation;
        // Enqueue each demo location
        messageQueue.enqueueLocation(new LocationData(demoLocation, "demo_mode"));
        Log.d(TAG_LOCATION, "Enqueued demo mode locations");
    }

    /**
     * Creates a new Location object with the specified coordinates.
     * Sets default values for accuracy and timestamp.
     *
     * @param latitude  The latitude coordinate
     * @param longitude The longitude coordinate
     * @return A new Location object configured with the provided coordinates
     */
    private Location createLocation(double latitude, double longitude) {
        Location location = new Location("demo_provider");
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(1.0f); // Set accuracy as needed
        location.setTime(System.currentTimeMillis()); // Set current time
        return location;
    }

    /**
     * Updates the location data in SharedPreferences storage.
     * Stores the latitude, longitude, and a formatted UTC timestamp.
     *
     * @param location The location object containing the data to store
     */
    private void updateLocationStorage(Location location) {
        updateStorage("LAST_KNOWN_LAT", String.valueOf(location.getLatitude()));
        updateStorage("LAST_KNOWN_LON", String.valueOf(location.getLongitude()));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String formattedTime = sdf.format(new Date(location.getTime()));
        updateStorage(LAST_LOCATION_TIME, formattedTime);
    }

    /**
     * Logs location update information to both logcat and a file.
     * Includes details such as coordinates, accuracy, speed, and timestamp.
     *
     * @param location The location object to log
     */
    private void logLocationUpdate(Location location) {
        if (Log.isLoggable(TAG_LOCATION, Log.DEBUG)) {
            Log.d(TAG_LOCATION, String.format(Locale.US,
                    "Location: lat=%.6f, lng=%.6f, acc=%.1fm, speed=%.1fm/s, time=%d",
                    location.getLatitude(), location.getLongitude(),
                    location.getAccuracy(), location.getSpeed(),
                    location.getTime()));
        }

        // Also log to file for debugging
        String sb = "Location | " +
                "Speed => " + location.getSpeed() +
                " Lat => " + location.getLatitude() +
                " Lon => " + location.getLongitude() +
                " Accuracy => " + location.getAccuracy() +
                " TimeStamp => " + location.getTime();

        appendToFile(sb);
    }

    /**
     * Sends a batch of location updates to the server.
     * Implements rate limiting, wake lock handling, and error handling.
     * Converts location data to JSON format before sending.
     *
     * @param batch List of LocationData objects to send to the server
     */
    private void sendBatchToServer(List<LocationData> batch) {
        if (batch.isEmpty()) {
            isBatchProcessing.set(false);
            return;
        }

        // Acquire wake lock for network operation
        PowerManager.WakeLock wakeLock = acquireTemporaryWakeLock("SendBatchToServer", wakeLockTimeoutMs);

        try {
            // Convert batch to JSONArray
            JSONArray locationPayload = new JSONArray();
            for (LocationData data : batch) {
                locationPayload.put(data.toJsonObject());
            }

            Log.i(TAG_BATCH, "Sending batch of " + batch.size() + " locations to server");

            // Get API client and headers
            MobilityCallAPI callAPIHandler = MobilityCallAPI.getInstance(context);
            Map<String, String> baseHeaders = MobilityCallAPI.getBaseHeaders(context);

            // Get API URL
            SharedPreferences sharedPref = context.getSharedPreferences(
                    context.getString(R.string.preference_file_key), MODE_PRIVATE);
            String baseUrl = sharedPref.getString("BASE_URL", "null");
            String orderUrl = baseUrl + "/driver/location";

            // Set headers
            baseHeaders.put("source", "batch_location_update");
            setVehicleAndMerchantHeaders(baseHeaders);

            // Log API request
            appendToFile("api_calls_requested", "Batch Location Api | " + batch.size() +
                    " locations | " + System.currentTimeMillis(), "location_service");

            // Make API call asynchronously
            callAPIHandler.callAPI(orderUrl, baseHeaders, locationPayload.toString(),
                    new MobilityCallAPI.APICallback() {
                        @Override
                        public void onResponse(MobilityAPIResponse apiResponse) {
                            handleApiResponse(apiResponse, batch);
                        }

                        @Override
                        public void onError(Exception error) {
                            Log.e(TAG_ERROR, "API call failed", error);
                            FirebaseCrashlytics.getInstance().recordException(error);
                            // Add back to queue on failure
                            messageQueue.addAll(batch);
                            isBatchProcessing.set(false);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error preparing batch", e);
            FirebaseCrashlytics.getInstance().recordException(e);
            // Add back to queue on failure
            messageQueue.addAll(batch);
            isBatchProcessing.set(false);
        } finally {
            // Release wake lock after operation
            releaseWakeLockIfHeld(wakeLock);
        }
    }

    /**
     * Sets vehicle and merchant-related headers for API requests.
     * Uses cached values when available, otherwise fetches from profile API.
     *
     * @param headers Map of headers to be populated with vehicle and merchant information
     */
    private void setVehicleAndMerchantHeaders(Map<String, String> headers) {
        String merchantId = getValueFromStorage("MERCHANT_ID");
        String vehicleVariant = getValueFromStorage("VEHICLE_VARIANT");
        String driverMode = getValueFromStorage("DRIVER_STATUS_N");

        if (merchantId != null && vehicleVariant != null && driverMode != null) {
            headers.put("mId", merchantId);
            headers.put("vt", vehicleVariant);
            headers.put("dm", driverMode.toUpperCase());
        } else if (vVariant != null && drMode != null && merchantID != null) {
            headers.put("mId", merchantID);
            headers.put("vt", vVariant);
            headers.put("dm", drMode);
        } else {
            // Fetch profile info and set headers
            fetchProfileAndSetHeaders(headers);
        }
    }

    /**
     * Fetches profile information from the server and sets the corresponding headers.
     * Updates local storage with merchant ID and vehicle variant information.
     *
     * @param headers Map of headers to be populated with profile information
     */
    private void fetchProfileAndSetHeaders(Map<String, String> headers) {
        try {
            MobilityCallAPI callAPIHandler = MobilityCallAPI.getInstance(context);
            SharedPreferences sharedPref = context.getSharedPreferences(
                    context.getString(R.string.preference_file_key), MODE_PRIVATE);
            String baseUrl = sharedPref.getString("BASE_URL", "null");

            MobilityAPIResponse apiResponse = callAPIHandler.callAPI(
                    baseUrl + "/driver/profile", headers, "{}", "GET");

            JSONObject resp = new JSONObject(apiResponse.getResponseBody());

            if (resp.has("mode")) {
                drMode = resp.get("mode").toString().toUpperCase();
                headers.put("dm", drMode);
            }

            JSONObject org = resp.optJSONObject("organization");
            if (org != null && org.has("id")) {
                merchantID = org.getString("id");
                headers.put("mId", merchantID);
                updateStorage("MERCHANT_ID", merchantID);
            }

            JSONObject vehicle = resp.optJSONObject("linkedVehicle");
            if (vehicle != null && vehicle.has("variant")) {
                vVariant = vehicle.getString("variant");
                headers.put("vt", vVariant);
                updateStorage("VEHICLE_VARIANT", vVariant);
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error fetching profile", e);
            Bundle params = new Bundle();
            params.putString("error", e.toString());
            FirebaseAnalytics.getInstance(context).logEvent("LS_ERROR_GETTING_PROFILE", params);
        }
    }

    /**
     * Handles API response after sending a location batch.
     * Processes success/failure cases and updates relevant state.
     * Notifies callbacks of successful updates or driver blocked status.
     *
     * @param apiResponse The response from the server
     * @param sentBatch   The batch of locations that was sent
     */
    private void handleApiResponse(MobilityAPIResponse apiResponse, List<LocationData> sentBatch) {
        int respCode = apiResponse.getStatusCode();
        String responseBody = apiResponse.getResponseBody();

        appendToFile("api_calls_responded", "Batch Location Api Response | Code: " +
                respCode + " | " + System.currentTimeMillis(), "location_service");

        Log.d(TAG_API, "API response: code=" + respCode + ", body=" + responseBody);

        if ((respCode < 200 || respCode >= 300) && respCode != 302) {
            // Handle error response
            handleApiError(respCode, responseBody, sentBatch);
        } else {
            // Success case - locations successfully sent
            Log.i(TAG_API, "Batch successfully sent to server");

            // Notify callbacks about successful location update
            notifyLocationUpdateSuccess();
        }

        // Check for token issues
        checkForTokenIssues(responseBody);

        // Mark processing as complete
        isBatchProcessing.set(false);
    }

    /**
     * Handles API error responses for location batch updates.
     * Determines if batch should be retried based on status code.
     * Handles specific error conditions like DRIVER_BLOCKED.
     *
     * @param respCode     HTTP response code from the server
     * @param responseBody Response body from the server
     * @param sentBatch    The batch of locations that failed to send
     */
    private void handleApiError(int respCode, String responseBody, List<LocationData> sentBatch) {
        try {
            // For client errors (4xx), don't retry
            boolean shouldRetry = respCode < 400 || respCode >= 500;

            if (shouldRetry) {
                Log.w(TAG_API, "Retryable error " + respCode + ", re-adding batch to queue");
                messageQueue.addAll(sentBatch);
            } else {
                Log.e(TAG_API, "Non-retryable error " + respCode + ", discarding batch");
            }

            // Parse error for specific cases like DRIVER_BLOCKED
            JSONObject errorPayload = new JSONObject(responseBody);
            String errorCode = errorPayload.optString("errorCode", "");

            if (respCode == 403 && errorCode.equals("DRIVER_BLOCKED")) {
                notifyDriverBlocked(errorCode);
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error parsing API response", e);
        }

        // Log the error to Firebase
        Exception exception = new Exception("API Error in batch location update for ID: " +
                driverId + " $ Resp Code: " + respCode + " $ Error: " + responseBody);
        FirebaseCrashlytics.getInstance().recordException(exception);
    }

    /**
     * Notifies registered callbacks about successful location updates.
     * Updates the stored timestamp and passes current location to all callbacks.
     */
    private void notifyLocationUpdateSuccess() {
        if (updateTimeCallbacks.isEmpty()) {
            return;
        }

        // Create formatted timestamp
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timestamp = f.format(new Date());

        // Update timestamp in storage
        updateStorage("DRIVER_LOCATION_TS", timestamp);

        // Notify all callbacks
        for (UpdateTimeCallback callback : updateTimeCallbacks) {
            callback.triggerUpdateTimeCallBack(
                    timestamp,
                    String.valueOf(lastLatitudeValue),
                    String.valueOf(lastLongitudeValue),
                    "SUCCESS"
            );
        }
    }

    /**
     * Notifies registered callbacks when driver is blocked.
     * Passes the error code and current location to all callbacks.
     *
     * @param errorCode The error code indicating why the driver was blocked
     */
    private void notifyDriverBlocked(String errorCode) {
        if (updateTimeCallbacks.isEmpty()) {
            return;
        }

        // Create formatted timestamp
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timestamp = f.format(new Date());

        // Notify all callbacks about driver being blocked
        for (UpdateTimeCallback callback : updateTimeCallbacks) {
            callback.triggerUpdateTimeCallBack(
                    timestamp,
                    String.valueOf(lastLatitudeValue),
                    String.valueOf(lastLongitudeValue),
                    errorCode
            );
        }
    }

    /**
     * Checks API response for token-related issues.
     * If token is invalid or expired, updates storage and stops the service.
     *
     * @param responseBody The API response body to check
     */
    private void checkForTokenIssues(String responseBody) {
        try {
            JSONObject resp = new JSONObject(responseBody != null && !responseBody.trim().isEmpty()
                    ? responseBody : "{}");

            if (resp.has("errorCode")) {
                String errorCode = resp.getString("errorCode");
                if (errorCode.equals("INVALID_TOKEN") || errorCode.equals("TOKEN_EXPIRED")) {
                    Log.w(TAG_API, "Invalid token detected: " + errorCode);
                    updateStorage("REGISTERATION_TOKEN", "__failed");
                    stopSelf();
                }
            }
        } catch (JSONException e) {
            Log.e(TAG_ERROR, "Error checking for token issues", e);
        }
    }

    /**
     * Cleans up resources when the service is being destroyed.
     * Unregisters listeners, stops updates, cancels timers, and saves cached data.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy()");

        // Unregister preference change listener
        if (sharedPrefs != null) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
        }

        // Stop location updates
        stopLocationUpdates();

        // Stop the location heartbeat timer
        stopLocationHeartbeatTimer();

        // Shutdown schedulers
        if (batchScheduler != null && !batchScheduler.isShutdown()) {
            batchScheduler.shutdown();
        }

        // Save any pending locations before shutting down
        if (messageQueue != null) {
            messageQueue.flushCache();
        }

        // Unregister network monitoring callbacks/receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                Log.d(TAG, "Unregistered network callback");
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Error unregistering network callback", e);
            }
        } else if (internetBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(internetBroadcastReceiver);
                Log.d(TAG, "Unregistered broadcast receiver");
            } catch (Exception e) {
                // Ignore if not registered
            }
        }

        // Stop GRPC service if running
        if (isServiceRunning(context, GRPCNotificationService.class.getName())) {
            Intent grpcServiceIntent = new Intent(context, GRPCNotificationService.class);
            context.stopService(grpcServiceIntent);
        }

        // Let the base class clean up
        super.onDestroy();
    }

    /**
     * Checks if location services are enabled on the device.
     *
     * @return true if location is enabled, false otherwise
     */
    private boolean isLocationDisabled() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return locationManager == null || !LocationManagerCompat.isLocationEnabled(locationManager);
    }

    /**
     * Updates driver status on the backend when location is disabled.
     * If setting to offline, also shows notification and stops the service.
     *
     * @param status true for online, false for offline
     */
    private void updateDriverStatus(boolean status) {
        Thread thread = new Thread(() -> {
            try {
                SharedPreferences sharedPref = context.getSharedPreferences(
                        context.getString(R.string.preference_file_key), MODE_PRIVATE);
                String baseUrl = sharedPref.getString("BASE_URL", "null");
                String modeStatus = status ? "ONLINE" : "OFFLINE";
                String orderUrl = baseUrl + "/driver/setActivity?active=" + status + "&mode=" + modeStatus;

                MobilityCallAPI mobilityApiHandler = MobilityCallAPI.getInstance(context);
                Map<String, String> baseHeaders = MobilityCallAPI.getBaseHeaders(context);
                mobilityApiHandler.callAPI(orderUrl, baseHeaders);

                if (!status) {
                    updateStorage("DRIVER_STATUS", "__failed");
                    showAlertNotification();
                    stopSelf();
                }
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Error updating driver status", e);
                FirebaseCrashlytics.getInstance().recordException(e);
            }
        });
        thread.start();
    }

    /**
     * Shows a notification informing the driver that they've been set offline due to location being disabled.
     */
    private void showAlertNotification() {
        Log.i(TAG, "Showing alert notification for disabled location");

        try {
            Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getActivity(this, alertNotificationId,
                    notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, "General");
            mBuilder.setLargeIcon(BitmapFactory.decodeResource(getResources(),
                            Utils.getResIdentifier(context, "ic_launcher", "drawable")))
                    .setContentTitle(getString(R.string.we_made_you_offline))
                    .setSmallIcon(Utils.getResIdentifier(context,
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                                    "ic_launcher_small_icon" : "ny_ic_launcher", "drawable"))
                    .setContentText(getString(R.string.location_is_turned_off_permission_is_disabled))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setContentIntent(pendingIntent);

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(alertNotificationId, mBuilder.build());
            }

            startGPSListeningService();
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error showing alert notification", e);
        }
    }

    /**
     * Starts the GPS listening service to monitor for location being enabled again.
     */
    private void startGPSListeningService() {
        try {
            Intent gpsListeningService = new Intent(this, GpsListeningService.class);
            SharedPreferences sharedPrefs = getApplicationContext().getSharedPreferences(
                    getString(R.string.preference_file_key), MODE_PRIVATE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    sharedPrefs.getString("ACTIVITY_STATUS", "null").equals("onPause")) {
                AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                Intent alarmIntent = new Intent(context, GPSBroadcastReceiver.class);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0,
                        alarmIntent, PendingIntent.FLAG_IMMUTABLE);
                manager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.getApplicationContext().startForegroundService(gpsListeningService);
            } else {
                this.startService(gpsListeningService);
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error starting GPS listening service", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Creates a notification channel for the foreground service notification.
     * Only executed on Android O (API 26) and above.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String description = "Location Update Service";
            NotificationChannel channel = new NotificationChannel(
                    LOCATION_UPDATES,
                    "Location Updates",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription(description);
            channel.setGroup("3_services");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Creates the notification for the foreground service.
     *
     * @return A configured Notification object
     */
    private Notification createNotification() {
        createNotificationChannel();

        Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 10,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, LOCATION_UPDATES)
                .setContentTitle("Updating")
                .setContentText(getString(R.string.your_location_is_being_updated))
                .setSmallIcon(Utils.getResIdentifier(context, (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                        "ic_launcher_small_icon" : "ny_ic_launcher", "drawable"))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    /**
     * Checks if the driver is near special pickup zones and updates the UI accordingly.
     * Uses geohashing to efficiently determine proximity to special zones.
     *
     * @param location The current driver location
     */
    @AddTrace(name = "checkNearByPickupZoneTrace")
    private void checkNearByPickupZone(Location location) {
        try {
            SharedPreferences sharedPref = context.getSharedPreferences(
                    context.getString(R.string.preference_file_key), MODE_PRIVATE);
            String rideStatus = getValueFromStorage("IS_RIDE_ACTIVE");
            String enableSpecialPickupWidget = getValueFromStorage("ENABLE_SPECIAL_PICKUP_WIDGET");

            if (location == null || rideStatus == null || enableSpecialPickupWidget == null ||
                    !rideStatus.equals("false") || !enableSpecialPickupWidget.equals("true")) {
                return;
            }

            String stringifiedZones = sharedPref.getString("SPECIAL_LOCATION_LIST", null);
            if (stringifiedZones == null) {
                return;
            }

            JSONArray zones = new JSONArray(stringifiedZones);
            GeoHash geoHash = new GeoHash();
            String currentGeoHash = geoHash.encode(location.getLatitude(), location.getLongitude(), 7);
            double nearbyGeoHashRadius = Double.parseDouble(
                    sharedPref.getString("SEARCH_SPECIAL_PICKUP_WITHIN_RADIUS", "150.0"));

            ArrayList<String> nearbyGeohashes = geoHash.nearbyGeohashes(currentGeoHash, nearbyGeoHashRadius);
            nearbyGeohashes.add(currentGeoHash);

            String currentZoneGeoHash = getValueFromStorage("CURRENT_ZONE_GEO_HASH");
            if (currentZoneGeoHash == null) {
                updateStorage("CURRENT_ZONE_GEO_HASH", currentGeoHash);
                currentZoneGeoHash = currentGeoHash;
            }

            boolean nearBySpecialPickup = false;
            for (int i = 0; i < zones.length(); i++) {
                JSONArray zoneMap = (JSONArray) zones.get(i);
                String zoneGeoHash = (String) zoneMap.get(0);
                nearBySpecialPickup = nearbyGeohashes.contains(zoneGeoHash);

                if (!currentZoneGeoHash.equals(zoneGeoHash) && nearBySpecialPickup) {
                    isSpecialpickup = true;
                    showWidget(true);
                    currentZoneGeoHash = zoneGeoHash;
                    updateStorage("CURRENT_ZONE_GEO_HASH", zoneGeoHash);
                }

                if (nearBySpecialPickup) {
                    isSpecialpickup = true;
                    break;
                }
            }

            if (!nearBySpecialPickup && isSpecialpickup) {
                isSpecialpickup = false;
                updateStorage("CURRENT_ZONE_GEO_HASH", currentGeoHash);
                showWidget(false);
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error checking nearby pickup zone", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Shows a widget when the driver is near a special pickup zone.
     * Only shows the widget when the application is in background.
     *
     * @param isSpecialPickupZone true if near a special zone, false otherwise
     */
    private void showWidget(boolean isSpecialPickupZone) {
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences(
                    context.getString(R.string.preference_file_key), MODE_PRIVATE);
            String activityStatus = sharedPreferences.getString("ACTIVITY_STATUS", "null");

            if ((activityStatus.equals("onPause") || activityStatus.equals("onDestroy"))) {
                Intent widgetService = new Intent(getApplicationContext(), WidgetService.class);

                if (isSpecialPickupZone) {
                    widgetService.putExtra("showNearbySpecialPickup", true);
                    widgetService.putExtra("specialPickupMessage",
                            getString(R.string.you_are_near_a_special_pickup_zone));
                } else {
                    widgetService.putExtra("showNearbySpecialPickup", false);
                }

                startService(widgetService);
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error showing widget", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Logs events for health check monitoring.
     * Used to track when the service is started by different mechanisms.
     *
     * @param intent The intent that started the service
     */
    private void logEventForHealthCheck(Intent intent) {
        if (intent != null) {
            String serviceStartingSource = intent.getStringExtra("StartingSource");
            if (serviceStartingSource != null) {
                if (serviceStartingSource.equals("TRIGGER_SERVICE")) {
                    FirebaseAnalytics.getInstance(this).logEvent("service_triggered_by_health_check", new Bundle());
                } else if (serviceStartingSource.equals("TRIGGER_SERVICE_INACTIVE")) {
                    FirebaseAnalytics.getInstance(this).logEvent("service_by_health_check_inactive", new Bundle());
                }
            }
        }
    }

    /**
     * Checks if the device has an active network connection.
     * Supports both modern (API 23+) and legacy methods.
     *
     * @param context The application context
     * @return true if network is available, false otherwise
     */
    private boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

        android.net.Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;

        android.net.NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && (
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    /**
     * Updates a value in SharedPreferences storage.
     *
     * @param key   The key to store the value under
     * @param value The string value to store
     */
    private void updateStorage(String key, String value) {
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.preference_file_key), MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(key, value);
        editor.apply();
    }

    /**
     * Retrieves a value from storage using KeyValueStore.
     *
     * @param key The key to retrieve
     * @return The stored value, or null if not found
     */
    @Nullable
    private String getValueFromStorage(String key) {
        return KeyValueStore.read(getApplicationContext(),
                getApplicationContext().getString(R.string.preference_file_key), key, null);
    }

    /**
     * Appends log data to a file with the default prefix.
     *
     * @param data The data to log
     */
    private void appendToFile(String data) {
        appendToFile("location_logs", data, "fusedLocationProvider");
    }

    /**
     * Appends log data to a specified file.
     *
     * @param file     The base filename
     * @param data     The data to log
     * @param provider The provider identifier to append to the filename
     */
    private void appendToFile(String file, String data, String provider) {
        if (context.getApplicationInfo().packageName.contains("debug")) {
            try {
                File downloadsDir = context.getDir("logs", MODE_PRIVATE);
                File logDump = new File(downloadsDir, file + "_" + provider + ".dump");

                if (!logDump.exists()) {
                    logDump.createNewFile();
                }

                try (FileWriter writer = new FileWriter(logDump, true)) {
                    writer.write("\n");
                    writer.write("stamp " + System.currentTimeMillis() + " | ");
                    writer.write(data);
                } catch (IOException e) {
                    Log.e(TAG_ERROR, "Error writing to log file", e);
                }
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Error creating log file", e);
            }
        }
    }

    /**
     * Starts a timer to periodically check and send location updates even when the device hasn't moved.
     */
    private void startLocationHeartbeatTimer() {
        if (locationHeartbeatTimer != null) {
            locationHeartbeatTimer.cancel();
        }

        locationHeartbeatTimer = new Timer("LocationHeartbeatTimer");
        int heartbeatIntervalMillis = locationUpdateInterval * 1000;

        locationHeartbeatTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkAndSendLocationHeartbeat();
            }
        }, heartbeatIntervalMillis, heartbeatIntervalMillis);

        Log.d(TAG_TIMER, "Started location heartbeat timer with interval: " +
                locationUpdateInterval + "s");
    }

    /**
     * Restarts the location heartbeat timer with current settings.
     */
    private void restartLocationHeartbeatTimer() {
        if (locationHeartbeatTimer != null) {
            locationHeartbeatTimer.cancel();
        }
        startLocationHeartbeatTimer();
    }

    /**
     * Stops the location heartbeat timer.
     */
    private void stopLocationHeartbeatTimer() {
        if (locationHeartbeatTimer != null) {
            locationHeartbeatTimer.cancel();
            locationHeartbeatTimer = null;
        }
    }

    /**
     * Checks if the current location is valid and sends it as a heartbeat.
     * If location is stale, fetches a fresh location.
     */
    private void checkAndSendLocationHeartbeat() {
        try {
            Log.d(TAG_TIMER, "Location heartbeat triggered");
            if (handleDemoMode()) return;

            // Check if we need to refresh the location
            if (isLocationStale()) {
                Log.d(TAG_TIMER, "Current location is stale, fetching new location");
                fetchFreshLocation();
            } else {
                // Send the last known location even if it hasn't changed
                if (lastCachedLocation != null) {
                    Log.d(TAG_TIMER, "Sending cached location: lat=" +
                            lastCachedLocation.getLatitude() + ", lng=" +
                            lastCachedLocation.getLongitude());

                    // Create location data and add to queue
                    Location newLocation = new Location(lastCachedLocation);
                    newLocation.setTime(System.currentTimeMillis());
                    LocationData locationData = new LocationData(newLocation, "location_heartbeat");
                    messageQueue.enqueueLocation(locationData);
                } else {
                    Log.d(TAG_TIMER, "No cached location available, fetching new location");
                    fetchFreshLocation();
                }
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error in location heartbeat", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Determines if the last known location is considered stale based on its age.
     *
     * @return true if location is stale, false if location is still fresh
     */
    private boolean isLocationStale() {
        try {
            // Check when the last location was fetched
            String lastFetchTimeStr = getValueFromStorage(LAST_LOCATION_FETCH_TIME);
            if (lastFetchTimeStr == null) {
                return true; // No record of last fetch time, so consider it stale
            }

            long lastFetchTime = Long.parseLong(lastFetchTimeStr);
            long currentTime = System.currentTimeMillis();
            long elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(currentTime - lastFetchTime);

            // If more than the threshold has passed, consider it stale
            boolean isStale = elapsedMinutes >= locationFreshnessThresholdMinutes;

            Log.d(TAG_TIMER, "Location staleness check: elapsed=" + elapsedMinutes +
                    "m, threshold=" + locationFreshnessThresholdMinutes + "m, isStale=" + isStale);

            return isStale;
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error checking location staleness", e);
            return true; // On error, fetch fresh location to be safe
        }
    }

    /**
     * Fetches a fresh location manually using getCurrentLocation.
     * Prevents multiple simultaneous requests.
     */
    @SuppressLint("MissingPermission")
    private void fetchFreshLocation() {
        // Check if a request is already in progress - if so, don't start another one
        if (isLocationRequestInProgress.get()) {
            Log.d(TAG_TIMER, "Location request already in progress, skipping new request");
            return;
        }

        // Acquire wake lock for location fetch
        PowerManager.WakeLock wakeLock = acquireTemporaryWakeLock("FetchFreshLocation", wakeLockTimeoutMs);

        try {
            // Check if a request is already in progress - if so, don't start another one
            if (isLocationRequestInProgress.get()) {
                Log.d(TAG_TIMER, "Location request already in progress, skipping new request");
                return;
            }

            // Check permissions
            if (ActivityCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG_ERROR, "No location permissions for manual location fetch");
                return;
            }

            // Check if location is enabled
            if (isLocationDisabled()) {
                Log.e(TAG_ERROR, "Location is disabled for manual location fetch");
                return;
            }

            // Set the flag to indicate a request is in progress
            isLocationRequestInProgress.set(true);

            Log.d(TAG_TIMER, "Starting manual location request");

            // Use getCurrentLocation for a one-time location request with timeout
            fusedLocationProviderClient.getCurrentLocation(
                            Utils.getLocationPriority("driver_current_location_priority"),
                            cancellationTokenSource.getToken())
                    .addOnSuccessListener(location -> {
                        try {
                            if (location != null) {
                                Log.d(TAG_TIMER, "Received fresh location: lat=" +
                                        location.getLatitude() + ", lng=" + location.getLongitude());

                                // Update last fetch time
                                updateStorage(LAST_LOCATION_FETCH_TIME, String.valueOf(System.currentTimeMillis()));

                                // Save as cached location
                                lastCachedLocation = location;

                                // Update storage
                                updateLocationStorage(location);

                                // Send to queue
                                LocationData locationData = new LocationData(location, "manual_fetch");
                                messageQueue.enqueueLocation(locationData);
                            } else {
                                Exception e = new Exception("Location Null");
                                FirebaseCrashlytics.getInstance().recordException(e);
                                Log.e(TAG_TIMER, "Received null location from manual fetch");
                            }
                        } finally {
                            // Reset the flag when request completes successfully
                            isLocationRequestInProgress.set(false);
                            Log.d(TAG_TIMER, "Manual location request completed");
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG_ERROR, "Failed to get current location", e);
                        FirebaseCrashlytics.getInstance().recordException(e);

                        // Reset the flag when request fails
                        isLocationRequestInProgress.set(false);
                        Log.d(TAG_TIMER, "Manual location request failed");
                    });
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error fetching fresh location", e);
            FirebaseCrashlytics.getInstance().recordException(e);

            // Reset the flag if an exception occurs
            isLocationRequestInProgress.set(false);
            Log.d(TAG_TIMER, "Manual location request failed with exception");
        } finally {
            // Release wake lock after operation
            releaseWakeLockIfHeld(wakeLock);
        }
    }

    /**
     * Sets up a listener for SharedPreferences changes.
     * Updates service configuration when preferences change.
     */
    private void setupPreferenceChangeListener() {
        prefChangeListener = (prefs, key) -> {
            try {
                Log.d(TAG_CONFIG, "SharedPreference changed: " + key);

                if (key != null) {
                    switch (key) {
                        case REGISTRATION_TOKEN_KEY:
                            // Check if token exists or has been changed to an invalid value
                            String token = prefs.getString(REGISTRATION_TOKEN_KEY, null);
                            if (token == null || token.equals("__failed") || token.equals("null")) {
                                Log.w(TAG, "Registration token is invalid or missing, stopping location service");
                                stopSelf();
                                return; // Return early as service is being stopped
                            }
                            break;
                        case LOCATION_SERVICE_VERSION:
                            String version = prefs.getString(LOCATION_SERVICE_VERSION, null);
                            if (version != null && version.equals("V1")) {
                                Log.w(TAG, "Location Service swapped to V1, stopping location service");
                                stopSelf();
                                return; // Return early as service is being stopped
                            }
                            break;
                        case LOCATION_UPDATE_INTERVAL:
                            String updateIntervalStr = prefs.getString(key, null);
                            if (updateIntervalStr != null) {
                                int newInterval = Integer.parseInt(updateIntervalStr);
                                if (newInterval != locationUpdateInterval) {
                                    locationUpdateInterval = newInterval;
                                    Log.d(TAG_CONFIG, "Location update interval changed to " + newInterval + "s");
                                    if (isLocationUpdating) {
                                        stopLocationUpdates();
                                        startLocationUpdates();
                                    }
                                    restartLocationHeartbeatTimer();
                                }
                            }
                            break;

                        case LOCATION_BATCH_INTERVAL:
                            String batchIntervalStr = prefs.getString(key, null);
                            if (batchIntervalStr != null) {
                                int newBatchInterval = Integer.parseInt(batchIntervalStr);
                                if (newBatchInterval != locationBatchInterval) {
                                    locationBatchInterval = newBatchInterval;
                                    Log.d(TAG_CONFIG, "Batch interval changed to " + newBatchInterval + "s");
                                    restartBatchScheduler();
                                }
                            }
                            break;

                        case DRIVER_MIN_DISPLACEMENT:
                            String minDisplacementStr = prefs.getString(key, null);
                            if (minDisplacementStr != null) {
                                float newDisplacement = Float.parseFloat(minDisplacementStr);
                                if (newDisplacement != locationMinDisplacement) {
                                    locationMinDisplacement = newDisplacement;
                                    Log.d(TAG_CONFIG, "Min displacement changed to " + newDisplacement + "m");
                                    if (isLocationUpdating) {
                                        stopLocationUpdates();
                                        startLocationUpdates();
                                    }
                                }
                            }
                            break;

                        case LOCATION_BATCH_SIZE:
                            String batchSizeStr = prefs.getString(key, null);
                            if (batchSizeStr != null) {
                                locationBatchSize = Integer.parseInt(batchSizeStr);
                                Log.d(TAG_CONFIG, "Batch size changed to " + locationBatchSize);
                            }
                            break;

                        case LOCATION_FRESHNESS_THRESHOLD:
                            String freshnessThresholdStr = prefs.getString(key, null);
                            if (freshnessThresholdStr != null) {
                                locationFreshnessThresholdMinutes = Integer.parseInt(freshnessThresholdStr);
                                Log.d(TAG_CONFIG, "Location freshness threshold changed to " +
                                        locationFreshnessThresholdMinutes + "m");
                            }
                            break;

                        case LOCATION_MAX_BATCH_AGE_KEY:
                            String maxBatchAgeStr = prefs.getString(key, null);
                            if (maxBatchAgeStr != null) {
                                locationMaxBatchAgeSeconds = Integer.parseInt(maxBatchAgeStr);
                                Log.d(TAG_CONFIG, "Max batch age changed to " + locationMaxBatchAgeSeconds + "s");
                            }
                            break;

                        case "DRIVER_RIDE_STATUS":
                            driverRideStatus = prefs.getString(key, "IDLE");
                            Log.d(TAG_CONFIG, "Driver ride status changed to " + driverRideStatus);
                            if (isLocationUpdating) {
                                stopLocationUpdates();
                                startLocationUpdates();
                            }
                            break;

                        case LOCATION_RATE_LIMIT_SECONDS:
                            String rateLimitStr = prefs.getString(key, "2");
                            rateLimitTimeInSeconds = Long.parseLong(rateLimitStr); // Update local variable
                            Log.d(TAG_CONFIG, "Rate limiting time updated to " + rateLimitTimeInSeconds + " seconds");
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Error handling SharedPreferences change for key: " + key, e);
                FirebaseCrashlytics.getInstance().recordException(e);
            }
        };

        sharedPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener);

        // Also check token validity on startup
        checkRegistrationTokenValidity();
    }

    /**
     * Validates the registration token and stops service if invalid.
     */
    private void checkRegistrationTokenValidity() {
        String token = sharedPrefs.getString(REGISTRATION_TOKEN_KEY, null);
        if (token == null || token.equals("__failed") || token.equals("null")) {
            Log.w(TAG, "Registration token is invalid or missing on service start, stopping location service");
            stopSelf();
        }
    }

    /**
     * Checks if a valid registration token exists.
     *
     * @return true if token exists, false otherwise
     */
    private boolean checkRegistrationTokenExistence() {
        String token = sharedPrefs.getString(REGISTRATION_TOKEN_KEY, null);
        return !(token == null || token.equals("__failed") || token.equals("null"));
    }

    /**
     * Restarts the batch scheduler with current settings.
     */
    private void restartBatchScheduler() {
        if (batchScheduler != null && !batchScheduler.isShutdown()) {
            batchScheduler.shutdown();
        }
        startBatchScheduler();
    }

    /**
     * Loads wake lock configuration from MobilityRemoteConfigs as a JSON object.
     * Determines whether wake locks should be used and their timeout.
     */
    private void loadWakeLockConfig() {
        try {
            // Get wake lock config as a string (JSON object)
            String wakeLockConfigJson = remoteConfigs.getString(CONFIG_WAKE_LOCK);
            if (wakeLockConfigJson.isEmpty()) {
                wakeLockConfigJson = DEFAULT_WAKE_LOCK_CONFIG;
            }

            // Parse the JSON object
            JSONObject config = new JSONObject(wakeLockConfigJson);

            // Extract values
            useWakeLock = config.optBoolean("enabled", false);
            wakeLockTimeoutMs = config.optLong("timeout_ms", 30000);

            Log.d(TAG_CONFIG, "Wake lock config loaded: enabled=" + useWakeLock +
                    ", timeout=" + wakeLockTimeoutMs + "ms");
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Error loading wake lock config, defaulting to disabled", e);
            useWakeLock = false;
            wakeLockTimeoutMs = 30000;
        }
    }

    /**
     * Acquires a temporary wake lock for a specific task if wake locks are enabled.
     *
     * @param tag       Identifier for the wake lock (for debugging)
     * @param timeoutMs Maximum time the wake lock should be held in milliseconds
     * @return The acquired wake lock or null if wake locks are disabled
     */
    private PowerManager.WakeLock acquireTemporaryWakeLock(String tag, long timeoutMs) {
        if (!useWakeLock || powerManager == null) {
            return null;
        }

        // Create a new WakeLock with a specific tag for debugging
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationUpdateService::" + tag);

        // Set a timeout to ensure we don't hold it too long
        wakeLock.acquire(timeoutMs > 0 ? timeoutMs : wakeLockTimeoutMs);
        Log.d(TAG, "Acquired temporary wake lock: " + tag +
                " for " + (timeoutMs > 0 ? timeoutMs : wakeLockTimeoutMs) + "ms");
        return wakeLock;
    }

    /**
     * Releases the wake lock if it is held.
     *
     * @param wakeLock The wake lock to release
     */
    private void releaseWakeLockIfHeld(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Released temporary wake lock");
        }
    }

    /**
     * Callback interface for notifying components about location updates.
     */
    public interface UpdateTimeCallback {
        /**
         * Called when location is updated or an error occurs.
         *
         * @param time      The formatted timestamp
         * @param lat       The latitude as string
         * @param lng       The longitude as string
         * @param errorCode SUCCESS or an error code
         */
        void triggerUpdateTimeCallBack(String time, String lat, String lng, String errorCode);
    }

    /**
     * Class representing a single location data point for batching.
     * Includes conversion to/from JSON for storage and transmission.
     */
    static class LocationData {
        String id;
        double latitude;
        double longitude;
        float accuracy;
        float bearing;
        float speed;
        long timestamp;
        String source;

        /**
         * Creates a LocationData object from a Location and source identifier.
         *
         * @param location The Location object
         * @param source   The source identifier (e.g., "fused_location_provider")
         */
        LocationData(Location location, String source) {
            this.id = UUID.randomUUID().toString();
            this.latitude = location.getLatitude();
            this.longitude = location.getLongitude();
            this.accuracy = location.getAccuracy();
            this.bearing = location.hasBearing() ? location.getBearing() : 0.0f;
            this.speed = location.hasSpeed() ? location.getSpeed() : 0.0f;
            this.timestamp = location.getTime();
            this.source = source;
        }

        // Empty constructor for JSON deserialization
        LocationData() {
        }

        /**
         * Creates a LocationData object from a JSONObject.
         *
         * @param json The JSONObject containing location data
         * @return A new LocationData object
         * @throws JSONException if there's an error parsing the JSON
         */
        static LocationData fromJsonObject(JSONObject json) throws JSONException {
            LocationData data = new LocationData();

            data.id = json.optString("id", UUID.randomUUID().toString());

            JSONObject point = json.getJSONObject("pt");
            data.latitude = point.getDouble("lat");
            data.longitude = point.getDouble("lon");

            data.accuracy = (float) json.optDouble("acc", 0.0);
            data.bearing = (float) json.optDouble("bear", 0.0);
            data.speed = (float) json.optDouble("v", 0.0);
            data.source = json.optString("source", "unknown");

            // Parse timestamp if available
            @Nullable
            String formattedTime = json.optString("ts", "");
            if (!formattedTime.isEmpty()) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date date = sdf.parse(formattedTime);
                    data.timestamp = date != null ? date.getTime() : System.currentTimeMillis();
                } catch (Exception e) {
                    Log.e(TAG_JSON, "Error parsing timestamp: " + formattedTime, e);
                    data.timestamp = System.currentTimeMillis();
                }
            } else {
                data.timestamp = System.currentTimeMillis();
            }

            return data;
        }

        /**
         * Converts this location data to a JSONObject for transmission or storage.
         *
         * @return JSONObject representation of this location
         * @throws JSONException if there's an error creating the JSON
         */
        JSONObject toJsonObject() throws JSONException {
            JSONObject locationObject = new JSONObject();
            JSONObject point = new JSONObject();
            point.put("lat", latitude);
            point.put("lon", longitude);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String formattedTime = sdf.format(new Date(timestamp));

            locationObject.put("pt", point);
            locationObject.put("ts", formattedTime);
            locationObject.put("acc", accuracy);
            locationObject.put("bear", bearing);
            locationObject.put("source", source);
            locationObject.put("v", speed);
            locationObject.put("id", id);

            return locationObject;
        }
    }

    /**
     * Custom message queue implementation for location batching.
     * Provides thread-safe handling of location updates with persistent caching.
     */
    class MessageQueue {
        private final Queue<LocationData> queue = new ConcurrentLinkedQueue<>();
        private final Handler handler;

        /**
         * Creates a new MessageQueue with the provided looper.
         * Sets up a handler to process queue messages.
         *
         * @param looper The looper to use for message processing
         */
        MessageQueue(Looper looper) {
            handler = new Handler(looper) {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    switch (msg.what) {
                        case MSG_LOCATION_UPDATE:
                            LocationData locationData = (LocationData) msg.obj;
                            queue.add(locationData);
                            saveQueueToCache();
                            boolean sendDueToTime = shouldSendBatchDueToTime();


                            // If we have enough locations, trigger a batch process
                            if (((queue.size() >= locationBatchSize) || sendDueToTime) && isNetworkAvailable(context)) {
                                processBatch();
                            }
                            break;

                        case MSG_BATCH_PROCESS:
                            processBatch();
                            break;

                        case MSG_CACHE_FLUSH:
                            saveQueueToCache();
                            break;
                    }
                }
            };
        }

        /**
         * Adds a location to the processing queue.
         * Thread-safe method that posts a message to the handler.
         *
         * @param locationData The location data to enqueue
         */
        void enqueueLocation(LocationData locationData) {

            Message msg = handler.obtainMessage(MSG_LOCATION_UPDATE, locationData);
            handler.sendMessage(msg);
        }

        /**
         * Triggers processing of the current batch.
         * Thread-safe method that posts a message to the handler.
         */
        void triggerBatchProcess() {
            handler.sendEmptyMessage(MSG_BATCH_PROCESS);
        }

        /**
         * Flushes the current queue state to cache.
         * Thread-safe method that posts a message to the handler.
         */
        void flushCache() {
            handler.sendEmptyMessage(MSG_CACHE_FLUSH);
        }

        /**
         * Drains up to maxSize items from the queue.
         *
         * @param maxSize Maximum number of items to drain
         * @return List of drained LocationData objects
         */
        List<LocationData> drainBatch(int maxSize) {
            List<LocationData> batch = new ArrayList<>();
            int size = Math.min(queue.size(), maxSize);

            for (int i = 0; i < size; i++) {
                LocationData data = queue.poll();
                if (data != null) {
                    batch.add(data);
                }
            }

            return batch;
        }

        /**
         * Returns the current size of the queue.
         *
         * @return Number of items in the queue
         */
        int size() {
            return queue.size();
        }

        /**
         * Adds all locations to the queue and saves to cache.
         *
         * @param locations List of locations to add
         */
        void addAll(List<LocationData> locations) {
            queue.addAll(locations);
            saveQueueToCache();
        }

        /**
         * Saves the current queue to cache (SharedPreferences and file).
         */
        private void saveQueueToCache() {
            try {
                // Copy to avoid concurrent modification
                List<LocationData> queueCopy = new ArrayList<>(queue);

                // Save to SharedPreferences using JSONArray
                JSONArray jsonArray = new JSONArray();
                for (LocationData data : queueCopy) {
                    try {
                        jsonArray.put(data.toJsonObject());
                    } catch (JSONException e) {
                        Log.e(TAG_JSON, "Error serializing location data", e);
                    }
                }

                updateStorage(LOCATION_CACHED_BATCH, jsonArray.toString());

                // Save to file for persistence
                saveLocationsToFile(jsonArray);

                Log.d(TAG_CACHE, "Saved " + queueCopy.size() + " locations to cache");
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Failed to save queue to cache", e);
                FirebaseCrashlytics.getInstance().recordException(e);
            }
        }

        /**
         * Saves locations to a file for persistence between app restarts.
         *
         * @param jsonArray JSONArray of location data to save
         */
        private void saveLocationsToFile(JSONArray jsonArray) {
            try {
                File file = new File(context.getFilesDir(), LOCATION_CACHE_FILE);
                FileWriter writer = new FileWriter(file);
                writer.write(jsonArray.toString());
                writer.close();
                Log.d(TAG_CACHE, "Saved " + jsonArray.length() + " locations to file");
            } catch (IOException e) {
                Log.e(TAG_ERROR, "Failed to save locations to file", e);
            }
        }

        /**
         * Restores locations from cache (tries SharedPreferences first, then file).
         */
        void restoreFromCache() {
            // First try SharedPreferences
            String cachedJson = getValueFromStorage(LOCATION_CACHED_BATCH);
            if (cachedJson != null && !cachedJson.isEmpty()) {
                try {
                    JSONArray jsonArray = new JSONArray(cachedJson);
                    List<LocationData> cachedLocations = convertJsonArrayToLocationList(jsonArray);

                    if (!cachedLocations.isEmpty()) {
                        queue.addAll(cachedLocations);
                        Log.i(TAG_CACHE, "Restored " + cachedLocations.size() + " locations from SharedPreferences");
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG_ERROR, "Failed to restore from SharedPreferences", e);
                }
            }

            // Try file backup
            try {
                File file = new File(context.getFilesDir(), LOCATION_CACHE_FILE);
                if (file.exists()) {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }
                    reader.close();

                    JSONArray jsonArray = new JSONArray(json.toString());
                    List<LocationData> cachedLocations = convertJsonArrayToLocationList(jsonArray);

                    if (!cachedLocations.isEmpty()) {
                        queue.addAll(cachedLocations);
                        Log.i(TAG_CACHE, "Restored " + cachedLocations.size() + " locations from file");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Failed to restore from file", e);
            }
        }

        /**
         * Converts a JSONArray to a list of LocationData objects.
         *
         * @param jsonArray The JSONArray to convert
         * @return List of LocationData objects
         */
        private List<LocationData> convertJsonArrayToLocationList(JSONArray jsonArray) {
            List<LocationData> locationList = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                try {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    LocationData locationData = LocationData.fromJsonObject(jsonObject);
                    locationList.add(locationData);
                } catch (JSONException e) {
                    Log.e(TAG_JSON, "Error deserializing location data at index " + i, e);
                }
            }

            return locationList;
        }

        /**
         * Processes the current batch of locations if conditions are met.
         * Sends to server if batch size threshold is reached or time threshold is exceeded.
         */
        private void processBatch() {
            if (isBatchProcessing.get() || queue.isEmpty() || !isNetworkAvailable(context)) {
                return;
            }

            if (batchProcessingLock.tryLock()) {
                try {
                    isBatchProcessing.set(true);

                    // Enforce rate limiting using the local variable
                    long currentTime = System.currentTimeMillis();
                    String lastSentTimeStr = getValueFromStorage(LAST_BATCH_SENT_TIME);
                    long lastSentTime = lastSentTimeStr != null ? Long.parseLong(lastSentTimeStr) : 0;
                    long timeSinceLastCall = currentTime - lastSentTime;

                    long rateLimitTimeInMillis = rateLimitTimeInSeconds * 1000; // Convert to milliseconds

                    if (timeSinceLastCall < rateLimitTimeInMillis) {
                        try {
                            Thread.sleep(rateLimitTimeInMillis - timeSinceLastCall);
                        } catch (InterruptedException e) {
                            Log.e(TAG_ERROR, "Rate limiting sleep interrupted", e);
                        }
                    }

                    // Check if we need to send regardless of batch size due to time
                    boolean sendDueToTime = shouldSendBatchDueToTime();

                    if (queue.size() >= locationBatchSize || sendDueToTime) {
                        // If sending due to time, log the reason
                        if (sendDueToTime && queue.size() < locationBatchSize) {
                            Log.d(TAG_BATCH, "Sending batch due to time elapsed since last batch");
                        }

                        // Determine batch size based on queue size
                        int batchSize = Math.min(locationBatchSize, queue.size());
                        List<LocationData> batch = drainBatch(batchSize);

                        if (!batch.isEmpty()) {
                            // Update the last sent time
                            updateStorage(LAST_BATCH_SENT_TIME, String.valueOf(System.currentTimeMillis()));
                            sendBatchToServer(batch);
                        } else {
                            isBatchProcessing.set(false);
                        }
                    } else {
                        isBatchProcessing.set(false);
                    }
                } finally {
                    batchProcessingLock.unlock();
                }
            }
        }

        /**
         * Determines if a batch should be sent based on time since the last batch.
         *
         * @return true if enough time has passed since the last batch, false otherwise
         */
        private boolean shouldSendBatchDueToTime() {
            if (queue.isEmpty()) {
                return false;
            }

            // Get last batch sent time
            String lastSentTimeStr = getValueFromStorage(LAST_BATCH_SENT_TIME);
            if (lastSentTimeStr == null) {
                // No record of last sent time, update it and don't send
                updateStorage(LAST_BATCH_SENT_TIME, String.valueOf(System.currentTimeMillis()));
                return false;
            }

            long lastSentTime = Long.parseLong(lastSentTimeStr);
            long currentTime = System.currentTimeMillis();
            long elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(currentTime - lastSentTime);

            // If more than the max batch age has passed, send the batch
            boolean shouldSendDueToTime = elapsedSeconds >= locationMaxBatchAgeSeconds;

            if (shouldSendDueToTime) {
                Log.d(TAG_BATCH, "Batch age: " + elapsedSeconds + "s exceeds max age: " +
                        locationMaxBatchAgeSeconds + "s, triggering send");
            }

            return shouldSendDueToTime;
        }
    }

    /**
     * Binder class providing access to this service instance.
     */
    public class LocalBinder extends Binder {
        /**
         * Returns the LocationUpdateService instance.
         *
         * @return This service instance
         */
        public LocationUpdateServiceV2 getService() {
            return LocationUpdateServiceV2.this;
        }
    }
}