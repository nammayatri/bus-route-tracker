// Global state
let currentPosition = null;
let selectedRoute = null;
let stops = [];
let isOnline = navigator.onLine;
let locationWatchId = null;

// --- New: State for filtered/selected stop ---
let filteredStops = [];
let selectedStopId = null;

// --- Location tracking optimization vars ---
let lastKnownPosition = null;
let uiUpdateInterval = null;
let lastPositionTimestamp = 0;
let locationAccuracy = 0;
let isMoving = false;
let movementThreshold = 10; // meters
let lastMovementCheck = 0;
let batteryLevel = 1.0;
let lowPowerMode = false;

// Adaptive intervals based on motion and battery state
const LOCATION_INTERVALS = {
    STATIONARY: 10000,      // 10 seconds when not moving
    MOVING: 3000,           // 3 seconds when moving
    LOW_BATTERY: 10000,     // 10 seconds when battery < 20%
    UI_UPDATE: 3000         // 3 seconds UI refresh
};

// Initialize the application
async function init() {
    // Add online/offline event listeners
    window.addEventListener('online', handleOnlineStatus);
    window.addEventListener('offline', handleOnlineStatus);
    
    // Check for geolocation support
    if (!navigator.geolocation) {
        showModal('Geolocation is not supported by your device');
        return;
    }
    
    // Request location permissions and check battery status
    try {
        const permission = await navigator.permissions.query({ name: 'geolocation' });
        if (permission.state === 'denied') {
            showModal('Location access is required for this app to function. Please enable location services in your browser or device settings.');
            return;
        }
        if (permission.state === 'prompt') {
            showModal('Please allow location access to use this app.');
        }
        
        // Check battery API if available
        if ('getBattery' in navigator) {
            const battery = await navigator.getBattery();
            batteryLevel = battery.level;
            updateBatteryStatus(battery);
            
            // Listen for battery changes
            battery.addEventListener('levelchange', () => updateBatteryStatus(battery));
            battery.addEventListener('chargingchange', () => updateBatteryStatus(battery));
        }
    } catch (error) {
        console.warn('Advanced APIs not supported:', error);
        showModal('Location access is required for this app to function. Please enable location services in your browser or device settings.');
        return;
    }
    
    await loadRoutes();
    initLocationTracking();
}

// Update battery status and adjust tracking accordingly
function updateBatteryStatus(battery) {
    batteryLevel = battery.level;
    const wasPreviouslyLowPower = lowPowerMode;
    
    // Enter low power mode if battery below 20% and not charging
    lowPowerMode = (batteryLevel < 0.2) && !battery.charging;
    
    // If power mode changed, restart location tracking with new settings
    if (lowPowerMode !== wasPreviouslyLowPower && locationWatchId !== null) {
        restartLocationTracking();
    }
}

// Handle online/offline status
function handleOnlineStatus() {
    isOnline = navigator.onLine;
    const statusElement = document.getElementById('connection-status');
    if (statusElement) {
        statusElement.textContent = isOnline ? 'Online' : 'Offline';
        statusElement.className = isOnline ? 'status online' : 'status offline';
    }
    
    // Only restart location tracking if we're online after being offline
    if (isOnline && !locationWatchId) {
        restartLocationTracking();
    }
}

// Show error message
function showError(message) {
    const errorDiv = document.createElement('div');
    errorDiv.className = 'error-message';
    errorDiv.textContent = message;
    errorDiv.style.textAlign = 'center';
    errorDiv.style.top = '10px';
    errorDiv.style.left = '10px';
    errorDiv.style.backgroundColor = 'rgba(255, 0, 0, 0.8)';
    errorDiv.style.color = 'white';
    errorDiv.style.padding = '10px';
    errorDiv.style.zIndex = '1000';
    errorDiv.style.margin = 'auto';
    errorDiv.style.borderRadius = '5px';
    document.body.appendChild(errorDiv);
    setTimeout(() => errorDiv.remove(), 5000);
}

// Load routes from the backend
async function loadRoutes() {
    try {
        const response = await fetch('/api/routes');
        if (!response.ok) {
            if (response.status === 401) {
                window.location.href = '/record-data/login';
                return;
            }
            throw new Error('Failed to load routes');
        }
        let routes = await response.json();
        routes = routes.sort((a, b) => String(a.route_code).localeCompare(String(b.route_code), undefined, {numeric: true, sensitivity: 'base'}));
        const select = document.getElementById('routeSelect');
        select.innerHTML = '<option value="">Select a route...</option>';
        routes.forEach(route => {
            const option = document.createElement('option');
            option.value = route.route_code;
            option.textContent = `${route.route_name} | (TOWARDS ${route.route_end_point || ''})`;
            select.appendChild(option);
        });
    } catch (error) {
        console.error('Error loading routes:', error);
        showError('Failed to load routes. Please check your connection.');
    }
}

// --- New: Render stops UI ---
function renderStopsUI() {
    const searchValue = document.getElementById('stopSearchInput')?.value?.trim().toLowerCase() || '';
    // Always search from the full stops list
    let stopsToShow = stops;
    if (searchValue) {
        stopsToShow = stops.filter(stop => stop.stop_name.toLowerCase().includes(searchValue));
    }
    // Sort by distance from current position
    if (currentPosition) {
        stopsToShow = stopsToShow.map(stop => ({
            ...stop,
            distance: calculateDistance(currentPosition.lat, currentPosition.lon, stop.lat, stop.lon)
        })).sort((a, b) => a.distance - b.distance);
    }
    filteredStops = stopsToShow;
    // Top 5 nearest
    const top5 = stopsToShow.slice(0, 3);
    const rest = stopsToShow.slice(3);

    // Render top 5
    const topStopsDiv = document.getElementById('topStops');
    if (topStopsDiv) {
        topStopsDiv.innerHTML = '';
        top5.forEach(stop => {
            const div = document.createElement('div');
            div.className = 'top-stop-item' + (selectedStopId === stop.stop_id ? ' selected' : '');
            const distKm = stop.distance ? (stop.distance / 1000).toFixed(2) : '?';
            div.textContent = `${stop.stop_name} (${distKm} km)`;
            div.onclick = () => selectStop(stop.stop_id);
            topStopsDiv.appendChild(div);
        });
    }

    // Render slider for rest
    const sliderDiv = document.getElementById('sliderStops');
    if (sliderDiv) {
        sliderDiv.innerHTML = '';
        rest.forEach(stop => {
            const div = document.createElement('div');
            div.className = 'slider-stop-item' + (selectedStopId === stop.stop_id ? ' selected' : '');
            const distKm = stop.distance ? (stop.distance / 1000).toFixed(2) : '?';
            div.textContent = `${stop.stop_name} (${distKm} km)`;
            div.onclick = () => selectStop(stop.stop_id);
            sliderDiv.appendChild(div);
        });
    }

    // Update dropdown
    const stopSelect = document.getElementById('stopSelect');
    if (stopSelect) {
        stopSelect.innerHTML = '<option value="">Select a stop...</option>';
        // Sort stopsToShow by distance ascending
        const sortedStops = [...stopsToShow].sort((a, b) => (a.distance || 0) - (b.distance || 0));
        sortedStops.forEach(stop => {
            const option = document.createElement('option');
            const distKm = stop.distance ? (stop.distance / 1000).toFixed(2) : '?';
            option.value = stop.stop_id;
            option.textContent = `${stop.stop_name} (${distKm} km)`;
            if (stop.stop_id === selectedStopId) option.selected = true;
            stopSelect.appendChild(option);
        });
    }
}

// --- New: Select stop by id ---
function selectStop(stopId) {
    selectedStopId = stopId;
    const stopSelect = document.getElementById('stopSelect');
    if (stopSelect) stopSelect.value = stopId;
    handleStopSelection();
    renderStopsUI();
}

// Ensure handleStopSelection is called on dropdown change
document.addEventListener('DOMContentLoaded', () => {
    const stopSelectElem = document.getElementById('stopSelect');
    if (stopSelectElem) {
        stopSelectElem.addEventListener('change', handleStopSelection);
    }
});

// --- Update loadStops to use new UI ---
async function loadStops() {
    const routeSelectElem = document.getElementById('routeSelect');
    if (!routeSelectElem) return;
    
    const routeId = routeSelectElem.value;
    if (!routeId) {
        stops = [];
        selectedRoute = null;
        selectedStopId = null;
        renderStopsUI();
        return;
    }
    try {
        const response = await fetch(`/api/stops?route_id=${routeId}`);
        if (!response.ok) throw new Error('Failed to load stops');
        stops = await response.json();
        selectedRoute = routeId;
        selectedStopId = null;
        renderStopsUI();
    } catch (error) {
        console.error('Error loading stops:', error);
        showError('Failed to load stops. Please check your connection.');
    }
}

// Handle stop selection
function handleStopSelection() {
    // Always show manual input box
    const manualInput = document.getElementById('manualStopInput');
    if (manualInput) manualInput.classList.remove('hidden');
}

// Update location display
function updateLocationDisplay() {
    const locationDisplay = document.getElementById('current-location');
    if (locationDisplay && currentPosition) {
        locationDisplay.innerHTML = `<span class='current-location-icon'>📍</span> <b>${currentPosition.lat.toFixed(8)}, ${currentPosition.lon.toFixed(8)}</b>`;
        
        // Add accuracy information if available
        if (locationAccuracy) {
            locationDisplay.innerHTML += ` <small>(±${locationAccuracy.toFixed(1)}m)</small>`;
        }
    }
}

// ---- MAJOR OPTIMIZATION: Improved Location Tracking ----

// Initialize location tracking with a smarter approach
function initLocationTracking() {
    // Get immediate location once with high accuracy
    getOneTimeLocation();
    
    // Then start adaptive tracking that adjusts based on movement and battery
    startAdaptiveLocationTracking();
    
    // Start UI update interval - separate from location fetching
    startUIUpdateInterval();
}

// Get high-precision location once (for initial position)
function getOneTimeLocation() {
    showModal('Getting your location...', 'spinner');
    
    // Use getCurrentPosition with high accuracy for initial position
    navigator.geolocation.getCurrentPosition(
        position => {
            lastKnownPosition = {
                lat: position.coords.latitude,
                lon: position.coords.longitude,
                timestamp: position.timestamp
            };
            locationAccuracy = position.coords.accuracy;
            
            // Update current position for UI
            currentPosition = { 
                lat: position.coords.latitude,
                lon: position.coords.longitude
            };
            
            // Update UI
            updateLocationDisplay();
            hideModal();
            
            // Send to backend
            sendLocationToBackend(position.coords.latitude, position.coords.longitude);
            
            // Render stops if needed
            if (selectedRoute && stops.length > 0) {
                renderStopsUI();
            }
        },
        error => {
            console.error('Error getting initial location:', error);
            showModal('Could not get precise location. Please enable location services.', 'warning');
        },
        { 
            enableHighAccuracy: true, 
            timeout: 10000, 
            maximumAge: 5000
        }
    );
}

// Start adaptive location tracking that adjusts based on motion, battery, etc.
function startAdaptiveLocationTracking() {
    // Clear any existing watch
    if (locationWatchId !== null) {
        navigator.geolocation.clearWatch(locationWatchId);
    }
    
    // Calculate the appropriate tracking interval
    const trackingInterval = calculateTrackingInterval();
    
    // Set options based on our current state
    const options = {
        enableHighAccuracy: !lowPowerMode, // Lower accuracy in low power mode
        timeout: 10000,
        maximumAge: trackingInterval - 1000 // Slightly less than our interval
    };
    
    // Start watching position with adaptive settings
    locationWatchId = navigator.geolocation.watchPosition(
        handlePositionUpdate,
        handlePositionError,
        options
    );
}

// Handle position updates from the geolocation API
function handlePositionUpdate(position) {
    const newPosition = {
        lat: position.coords.latitude,
        lon: position.coords.longitude,
        timestamp: position.timestamp
    };
    
    // Update accuracy information
    locationAccuracy = position.coords.accuracy;
    
    // Check if we've moved significantly
    if (lastKnownPosition) {
        const distance = calculateDistance(
            lastKnownPosition.lat, 
            lastKnownPosition.lon,
            newPosition.lat, 
            newPosition.lon
        );
        
        // Update movement state if we moved more than threshold
        if (distance > movementThreshold) {
            isMoving = true;
            lastMovementCheck = Date.now();
        } else if (Date.now() - lastMovementCheck > 30000) {
            // If no significant movement for 30 seconds, consider stationary
            isMoving = false;
        }
        
        // If movement state changed, restart tracking with new interval
        if ((isMoving && trackingIsStationary()) || (!isMoving && !trackingIsStationary())) {
            restartLocationTracking();
        }
    }
    
    // Update our location state
    lastKnownPosition = newPosition;
    currentPosition = { 
        lat: newPosition.lat, 
        lon: newPosition.lon 
    };
    
    // Send to backend if online and it's a significant update
    if (isOnline && shouldSendToBackend(newPosition)) {
        sendLocationToBackend(newPosition.lat, newPosition.lon);
    }
}

// Decide if we should send this position update to the backend
function shouldSendToBackend(position) {
    // Always send if we don't have a last sent position
    if (!lastPositionTimestamp) return true;
    
    // Time-based throttling depending on movement
    const timeSinceLastSent = position.timestamp - lastPositionTimestamp;
    const minSendInterval = isMoving ? 5000 : 30000; // 5s moving, 30s stationary
    
    return timeSinceLastSent >= minSendInterval;
}

// Handle geolocation errors
function handlePositionError(error) {
    console.error('Location tracking error:', error);
    
    // Only show errors to user for permission issues
    if (error.code === error.PERMISSION_DENIED) {
        showError('Location permission denied. Please enable location services.');
    }
    
    // For timeout or position unavailable, retry with lower accuracy
    if (error.code === error.TIMEOUT || error.code === error.POSITION_UNAVAILABLE) {
        restartLocationTracking(true);
    }
}

// Calculate optimal tracking interval based on current state
function calculateTrackingInterval() {
    if (lowPowerMode) {
        return LOCATION_INTERVALS.LOW_BATTERY;
    }
    return isMoving ? LOCATION_INTERVALS.MOVING : LOCATION_INTERVALS.STATIONARY;
}

// Check if tracking is in stationary mode
function trackingIsStationary() {
    return calculateTrackingInterval() === LOCATION_INTERVALS.STATIONARY;
}

// Restart location tracking with potentially new settings
function restartLocationTracking(lowerAccuracy = false) {
    if (locationWatchId !== null) {
        navigator.geolocation.clearWatch(locationWatchId);
        locationWatchId = null;
    }
    
    // Short delay before restarting
    setTimeout(() => {
        // If we had trouble getting location, try with lower accuracy
        if (lowerAccuracy) {
            const options = { 
                enableHighAccuracy: false,
                timeout: 15000,
                maximumAge: 60000
            };
            
            locationWatchId = navigator.geolocation.watchPosition(
                handlePositionUpdate,
                error => console.error('Fallback location error:', error),
                options
            );
        } else {
            // Otherwise restart normal adaptive tracking
            startAdaptiveLocationTracking();
        }
    }, 500);
}

// Start interval for UI updates, separate from location fetching
function startUIUpdateInterval() {
    if (uiUpdateInterval) clearInterval(uiUpdateInterval);
    
    uiUpdateInterval = setInterval(() => {
        // Only update if we have a position
        if (currentPosition) {
            updateLocationDisplay();
            
            // Update stops UI if relevant
            if (selectedRoute && stops.length > 0) {
                renderStopsUI();
            }
        }
    }, LOCATION_INTERVALS.UI_UPDATE);
}

// --- Search input event ---
document.addEventListener('DOMContentLoaded', () => {
    const searchInput = document.getElementById('stopSearchInput');
    if (searchInput) {
        searchInput.addEventListener('input', () => {
            renderStopsUI();
        });
    }
});

// --- Modal helpers ---
function showModal(message, type = '') {
    const modal = document.getElementById('statusModal');
    const msg = document.getElementById('modalMessage');
    const modalContent = modal ? modal.querySelector('.modal-content') : null;
    if (msg) {
        if (message === 'Stop recorded successfully!') {
            msg.innerHTML = `<span class='success-checkmark'>
                <svg viewBox='0 0 52 52'><circle cx='26' cy='26' r='25' fill='none' stroke='#28a745' stroke-width='3'/><path fill='none' stroke='#28a745' stroke-width='4' d='M14 27l8 8 16-16'/></svg>
            </span><div>Stop recorded successfully!</div>`;
            if (modalContent) modalContent.classList.add('success');
        } else if (type === 'warning' || message.toLowerCase().includes('please select a route')) {
            msg.innerHTML = `<span style='display:block;margin:0 auto 10px auto;font-size:38px;color:#ff9800;'>⚠️</span><div>${message}</div>`;
            if (modalContent) modalContent.classList.remove('success');
            setTimeout(hideModal, 1500);
        } else if (type === 'spinner' || message.toLowerCase().includes('getting your location')) {
            msg.innerHTML = `<span class='spinner'></span><div style='margin-top:12px;'>${message}</div>`;
            if (modalContent) modalContent.classList.remove('success');
        } else {
            msg.textContent = message;
            if (modalContent) modalContent.classList.remove('success');
        }
    }
    if (modal) modal.classList.remove('hidden');
}

function hideModal() {
    const modal = document.getElementById('statusModal');
    if (modal) modal.classList.add('hidden');
}

// --- Manual Confirm Modal helpers ---
function showManualConfirmModal(stopName, lat, lon, onSubmit) {
    const modal = document.getElementById('manualConfirmModal');
    const text = document.getElementById('manualConfirmText');
    const submitBtn = document.getElementById('manualConfirmSubmit');
    const cancelBtn = document.getElementById('manualConfirmCancel');
    if (text) text.innerHTML = `Record for <b>${stopName}</b> at <br>Lat: <b>${lat.toFixed(6)}</b><br>Lon: <b>${lon.toFixed(6)}</b>?`;
    if (modal) modal.classList.remove('hidden');
    // Remove previous listeners
    if (submitBtn) {
        submitBtn.onclick = null;
        submitBtn.onclick = () => {
            modal.classList.add('hidden');
            onSubmit();
        };
    }
    if (cancelBtn) {
        cancelBtn.onclick = null;
        cancelBtn.onclick = () => {
            modal.classList.add('hidden');
        };
    }
}

// --- Optimized recording with smart location selection ---
async function startRecording() {
    if (!selectedRoute) {
        showModal('Please select a route', 'warning');
        return;
    }
    const stopSelect = document.getElementById('stopSelect');
    const manualInput = document.getElementById('manualStopInput');
    let stopToRecord = null;
    let stopName = '';
    let stopId = '';
    if (!stopSelect || stopSelect.value === '') {
        if (!manualInput) {
            showModal('UI elements not found', 'warning');
            return;
        }
        stopName = manualInput.value.trim();
        if (!stopName) {
            showModal('Please enter a stop name.');
            setTimeout(hideModal, 1500);
            return;
        }
        stopId = null;
    } else {
        stopToRecord = stops.find(s => s.stop_id === stopSelect.value);
        stopName = stopToRecord ? stopToRecord.stop_name : '';
        stopId = stopToRecord ? stopToRecord.stop_id : '';
    }
    if (!navigator.geolocation) {
        showModal('Location services are not available. Please enable location in your device settings.');
        return;
    }
    try {
        const permission = await navigator.permissions.query({ name: 'geolocation' });
        if (permission.state === 'denied') {
            showModal('Location access is required to record a stop. Please enable location services in your browser or device settings.');
            return;
        }
    } catch (error) {
        showModal('Location access is required to record a stop. Please enable location services in your browser or device settings.');
        return;
    }
    const now = Date.now();
    const lastPositionAge = lastKnownPosition ? (now - lastKnownPosition.timestamp) : Infinity;
    if (!isMoving && lastKnownPosition && lastPositionAge < 10000 && locationAccuracy < 30) {
        showManualConfirmModal(stopName, lastKnownPosition.lat, lastKnownPosition.lon, () => {
            recordStopWithLocation(stopName, stopId, lastKnownPosition.lat, lastKnownPosition.lon);
        });
    } else if (lastKnownPosition && lastPositionAge < 5000 && locationAccuracy < 20) {
        showManualConfirmModal(stopName, lastKnownPosition.lat, lastKnownPosition.lon, () => {
            recordStopWithLocation(stopName, stopId, lastKnownPosition.lat, lastKnownPosition.lon);
        });
    } else {
        showModal('Getting precise location...', 'spinner');
        navigator.geolocation.getCurrentPosition(
            position => {
                hideModal();
                const freshLat = position.coords.latitude;
                const freshLon = position.coords.longitude;
                lastKnownPosition = {
                    lat: freshLat,
                    lon: freshLon,
                    timestamp: position.timestamp
                };
                currentPosition = { lat: freshLat, lon: freshLon };
                locationAccuracy = position.coords.accuracy;
                showManualConfirmModal(stopName, freshLat, freshLon, () => {
                    recordStopWithLocation(stopName, stopId, freshLat, freshLon);
                });
            },
            error => {
                if (error.code === error.PERMISSION_DENIED) {
                    showModal('Location access is required to record a stop. Please enable location services in your browser or device settings.');
                } else {
                    showModal('Could not get precise location. Please try again.');
                    setTimeout(hideModal, 2000);
                }
            },
            {
                enableHighAccuracy: true,
                timeout: 15000,
                maximumAge: 0
            }
        );
    }
}

// Record stop with provided location
function recordStopWithLocation(stopName, stopId, lat, lon) {
    showModal('Recording stop...');
    
    // Mark this as the last time we sent data to backend
    lastPositionTimestamp = Date.now();
    
    fetch('/api/record', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            route_id: selectedRoute,
            stop_id: stopId,
            stop_name: stopName,
            lat: lat,
            lon: lon,
            accuracy: locationAccuracy || null,
            timestamp: new Date().toISOString()
        })
    })
    .then(async response => {
        if (!response.ok) throw new Error('Failed to record stop');
        await response.json();
        showModal('Stop recorded successfully!');
        setTimeout(() => {
            hideModal();
            const confirmPanel = document.getElementById('confirmationPanel');
            const manualInput = document.getElementById('manualStopInput');
            if (confirmPanel) confirmPanel.classList.add('hidden');
            if (manualInput) manualInput.classList.add('hidden');
        }, 1500);
    })
    .catch(error => {
        console.error('Error recording stop:', error);
        showModal('Failed to record stop. Please try again.');
        setTimeout(hideModal, 2000);
    });
}

// Find nearest stop based on coordinates
function findNearestStop(lat, lon) {
    return stops.reduce((nearest, stop) => {
        const distance = calculateDistance(lat, lon, stop.lat, stop.lon);
        if (!nearest || distance < nearest.distance) {
            return { ...stop, distance };
        }
        return nearest;
    }, null);
}

// Calculate distance between two points using Haversine formula
function calculateDistance(lat1, lon1, lat2, lon2) {
    const R = 6371e3; // Earth's radius in meters
    const φ1 = lat1 * Math.PI/180;
    const φ2 = lat2 * Math.PI/180;
    const Δφ = (lat2-lat1) * Math.PI/180;
    const Δλ = (lon2-lon1) * Math.PI/180;

    const a = Math.sin(Δφ/2) * Math.sin(Δφ/2) +
            Math.cos(φ1) * Math.cos(φ2) *
            Math.sin(Δλ/2) * Math.sin(Δλ/2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));

    return R * c;
}

// Show success message
function showSuccess(message) {
    const successDiv = document.createElement('div');
    successDiv.className = 'success-message';
    successDiv.textContent = message;
    document.body.appendChild(successDiv);
    setTimeout(() => successDiv.remove(), 3000);
}

// Logout function
function logout() {
    window.location.href = '/logout';
}

// Send location update to backend with debouncing & throttling
function sendLocationToBackend(lat, lon) {
    // Only send if we're online
    if (!isOnline) return;
    
    // Update timestamp of last sent position
    lastPositionTimestamp = Date.now();
    
    fetch('/api/location-update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            lat,
            lon,
            accuracy: locationAccuracy || null,
            timestamp: new Date().toISOString(),
            route_id: selectedRoute,
            stop_id: selectedStopId || null,
            is_moving: isMoving
        })
    }).catch(err => {
        console.error('Failed to send location update:', err);
    });
}

// Initialize the application when the page loads
document.addEventListener('DOMContentLoaded', init);

// Clean up when the page is unloaded or hidden
window.addEventListener('beforeunload', cleanUp);
document.addEventListener('visibilitychange', handleVisibilityChange);

// Handle tab/app visibility changes to save battery
function handleVisibilityChange() {
    if (document.visibilityState === 'hidden') {
        // Reduce tracking when tab is not visible
        if (locationWatchId !== null) {
            navigator.geolocation.clearWatch(locationWatchId);
            locationWatchId = null;
        }
        if (uiUpdateInterval !== null) {
            clearInterval(uiUpdateInterval);
            uiUpdateInterval = null;
        }
    } else if (document.visibilityState === 'visible') {
        // Resume tracking when tab becomes visible again
        if (!locationWatchId) {
            startAdaptiveLocationTracking();
        }
        if (!uiUpdateInterval) {
            startUIUpdateInterval();
        }
    }
}

// Clean up resources
function cleanUp() {
    if (locationWatchId !== null) {
        navigator.geolocation.clearWatch(locationWatchId);
        locationWatchId = null;
    }
    
    if (uiUpdateInterval !== null) {
        clearInterval(uiUpdateInterval);
        uiUpdateInterval = null;
    }
}