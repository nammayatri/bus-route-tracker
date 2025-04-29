// Global state
let currentPosition = null;
let selectedRoute = null;
let stops = [];
let isOnline = navigator.onLine;
let locationWatchId = null;

// --- New: State for filtered/selected stop ---
let filteredStops = [];
let selectedStopId = null;

// --- UI Lat/Lon update interval ---
let lastKnownPosition = null;
let uiUpdateInterval = null;

let locationUpdateInterval = 2000;

// Initialize the application
async function init() {
    // Add online/offline event listeners
    window.addEventListener('online', handleOnlineStatus);
    window.addEventListener('offline', handleOnlineStatus);
    
    // Check for geolocation support
    if (!navigator.geolocation) {
        showError('Geolocation is not supported by your device');
        return;
    }
    
    // Request location permissions
    try {
        const permission = await navigator.permissions.query({ name: 'geolocation' });
        if (permission.state === 'denied') {
            showError('Location access is required for this app to function');
            return;
        }
    } catch (error) {
        console.warn('Permissions API not supported:', error);
    }
    
    await loadRoutes();
    startLocationTracking();
}

// Handle online/offline status
function handleOnlineStatus() {
    isOnline = navigator.onLine;
    const statusElement = document.getElementById('connection-status');
    if (statusElement) {
        statusElement.textContent = isOnline ? 'Online' : 'Offline';
        statusElement.className = isOnline ? 'status online' : 'status offline';
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
        
        const routes = await response.json();
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
    const searchValue = document.getElementById('stopSearchInput').value.trim().toLowerCase();
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
    topStopsDiv.innerHTML = '';
    top5.forEach(stop => {
        const div = document.createElement('div');
        div.className = 'top-stop-item' + (selectedStopId === stop.stop_id ? ' selected' : '');
        const distKm = stop.distance ? (stop.distance / 1000).toFixed(2) : '?';
        div.textContent = `${stop.stop_name} (${distKm} km)`;
        div.onclick = () => selectStop(stop.stop_id);
        topStopsDiv.appendChild(div);
    });

    // Render slider for rest
    const sliderDiv = document.getElementById('sliderStops');
    sliderDiv.innerHTML = '';
    rest.forEach(stop => {
        const div = document.createElement('div');
        div.className = 'slider-stop-item' + (selectedStopId === stop.stop_id ? ' selected' : '');
        const distKm = stop.distance ? (stop.distance / 1000).toFixed(2) : '?';
        div.textContent = `${stop.stop_name} (${distKm} km)`;
        div.onclick = () => selectStop(stop.stop_id);
        sliderDiv.appendChild(div);
    });

    // Update dropdown
    const stopSelect = document.getElementById('stopSelect');
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

// --- New: Select stop by id ---
function selectStop(stopId) {
    selectedStopId = stopId;
    const stopSelect = document.getElementById('stopSelect');
    stopSelect.value = stopId;
    handleStopSelection();
    renderStopsUI();
}

// Ensure handleStopSelection is called on dropdown change
const stopSelectElem = document.getElementById('stopSelect');
if (stopSelectElem) {
    stopSelectElem.addEventListener('change', handleStopSelection);
}

// --- Update loadStops to use new UI ---
async function loadStops() {
    const routeId = document.getElementById('routeSelect').value;
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
    manualInput.classList.remove('hidden');
}

// Update location display
function updateLocationDisplay() {
    const locationDisplay = document.getElementById('current-location');
    if (locationDisplay && currentPosition) {
        locationDisplay.innerHTML = `<span class='current-location-icon'>📍</span> <b>${currentPosition.lat.toFixed(8)}, ${currentPosition.lon.toFixed(8)}</b>`;
    }
}

// --- Update location tracking to refresh stops UI ---
function startLocationTracking() {
    const options = {
        enableHighAccuracy: true,
        timeout: 5000,
        maximumAge: 3000
    };
    locationWatchId = navigator.geolocation.watchPosition(
        position => {
            lastKnownPosition = {
                lat: position.coords.latitude,
                lon: position.coords.longitude
            };
            // Send location update to backend every time we get a new position
            sendLocationToBackend(position.coords.latitude, position.coords.longitude);
        },
        error => {
            console.error('Error getting location:', error);
            showError('Error getting location. Please ensure location services are enabled.');
        },
        options
    );
    // Update UI every 2 seconds
    if (uiUpdateInterval) clearInterval(uiUpdateInterval);
    uiUpdateInterval = setInterval(() => {
        if (lastKnownPosition) {
            currentPosition = { ...lastKnownPosition };
            updateLocationDisplay();
            if (selectedRoute && stops.length > 0) {
                renderStopsUI();
            }
        }
    }, locationUpdateInterval);
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

// --- Show spinner and get initial location fast on home load ---
document.addEventListener('DOMContentLoaded', () => {
    showModal('Getting your location...', 'spinner');
    navigator.geolocation.getCurrentPosition(
        position => {
            lastKnownPosition = {
                lat: position.coords.latitude,
                lon: position.coords.longitude
            };
            currentPosition = { ...lastKnownPosition };
            updateLocationDisplay();
            hideModal();
            if (selectedRoute && stops.length > 0) {
                renderStopsUI();
            }
        },
        error => {
            showModal('Could not get your location. Please enable location services.', 'warning');
        },
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 3000 }
    );
});

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
    submitBtn.onclick = null;
    cancelBtn.onclick = null;
    submitBtn.onclick = () => {
        modal.classList.add('hidden');
        onSubmit();
    };
    cancelBtn.onclick = () => {
        modal.classList.add('hidden');
    };
}

// --- Update startRecording to use manual confirm modal and faster geolocation ---
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
    if (stopSelect.value === '') {
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
    // Always fetch latest location and show confirmation popup
    showModal('Getting location...');
    navigator.geolocation.getCurrentPosition(position => {
        hideModal();
        const freshLat = position.coords.latitude;
        const freshLon = position.coords.longitude;
        showManualConfirmModal(stopName, freshLat, freshLon, () => {
            recordStopWithFreshLocation(stopName, stopId, freshLat, freshLon);
        });
    }, error => {
        showModal('Could not get precise location. Please try again.');
        setTimeout(hideModal, 2000);
    }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 3000 });
}

function recordStopWithFreshLocation(stopName, stopId, lat, lon) {
    showModal('Recording stop...');
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
            timestamp: new Date().toISOString()
        })
    })
    .then(async response => {
        if (!response.ok) throw new Error('Failed to record stop');
        await response.json();
        showModal('Stop recorded successfully!');
        setTimeout(() => {
            hideModal();
            document.getElementById('confirmationPanel').classList.add('hidden');
            document.getElementById('manualStopInput').classList.add('hidden');
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

// Initialize the application when the page loads
document.addEventListener('DOMContentLoaded', init);

// Clean up when the page is unloaded
window.addEventListener('beforeunload', () => {
    if (locationWatchId) {
        navigator.geolocation.clearWatch(locationWatchId);
    }
});

function sendLocationToBackend(lat, lon) {
    let stop_id = selectedStopId || null;
    let stop_name = null;
    if (stop_id) {
        const stopObj = stops.find(s => s.stop_id === stop_id);
        stop_name = stopObj ? stopObj.stop_name : null;
    }
    fetch('/api/location-update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            lat,
            lon,
            timestamp: new Date().toISOString(),
            route_id: selectedRoute
        })
    }).catch(err => {
        console.error('Failed to send location update:', err);
    });
}
