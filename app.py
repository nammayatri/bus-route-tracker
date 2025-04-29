import uuid
from flask import Flask, request, jsonify, session, render_template, redirect, url_for, send_from_directory
from functools import wraps
import json
import os
from datetime import datetime, timedelta
import psycopg2
from clickhouse_driver import Client
import time
import requests
import logging


app = Flask(__name__)
app.secret_key = "your_secret_key_here"
app.permanent_session_lifetime = timedelta(days=365)  # Effectively permanent

# Dummy data for local testing
DUMMY_ROUTES = [
    {"route_number": "1", "tummoc_route_id": "route1", "route_name": "City Center - North Station"},
    {"route_number": "2", "tummoc_route_id": "route2", "route_name": "East Mall - West Terminal"},
    {"route_number": "3", "tummoc_route_id": "route3", "route_name": "South Park - Downtown"}
]

DUMMY_STOPS = {
    "route1": [
        {"stop_id": "stop1", "stop_name": "City Center", "lat": 12.9716, "lon": 77.5946},
        {"stop_id": "stop2", "stop_name": "North Station", "lat": 12.9784, "lon": 77.6408}
    ],
    "route2": [
        {"stop_id": "stop3", "stop_name": "East Mall", "lat": 12.9716, "lon": 77.6408},
        {"stop_id": "stop4", "stop_name": "West Terminal", "lat": 12.9784, "lon": 77.5946}
    ],
    "route3": [
        {"stop_id": "stop5", "stop_name": "South Park", "lat": 12.9750, "lon": 77.6000},
        {"stop_id": "stop6", "stop_name": "Downtown", "lat": 12.9750, "lon": 77.6300}
    ]
}

# File-based storage for local testing
LOCAL_DATA_FILE = "local_data.json"

# Configurable cache duration (in hours)
CACHE_HOURS = int(os.getenv('ROUTE_CACHE_HOURS', 1))  # Default: 1 hour

# In-memory cache
route_cache = {
    'routes': None,
    'routes_timestamp': 0,
    'stops': {},  # route_id: {'data': ..., 'timestamp': ...}
}

API_BASE_URL = os.getenv('API_BASE_URL', 'http://localhost:8090')
API_TOKEN = os.getenv('API_TOKEN', 'test')
API_CITY = os.getenv('API_CITY', 'chennai')
API_VEHICLE_TYPE = os.getenv('API_VEHICLE_TYPE', 'bus')

def load_local_data():
    try:
        with open(LOCAL_DATA_FILE, 'r') as f:
            content = f.read().strip()
            if not content:
                return {"stop_confirmations": []}
            return json.loads(content)
    except (FileNotFoundError, json.JSONDecodeError):
        return {"stop_confirmations": []}

def save_local_data(data):
    with open(LOCAL_DATA_FILE, 'w') as f:
        json.dump(data, f, indent=2)

# Database configurations with fallback
try:
    PG_CONFIG = {
        'dbname': 'mtc_replica',
        'user': os.getenv('DB_USER'),
        'password': os.getenv('DB_PASSWORD'),
        'host': os.getenv('DB_HOST', 'localhost')
    }
    conn = psycopg2.connect(**PG_CONFIG)
    conn.close()
    USE_DATABASE = True
except Exception as e:
    print(f"Database connection failed, using local storage: {e}")
    USE_DATABASE = False

try:
    CH_CLIENT = Client(
        host=os.getenv('CH_HOST', 'localhost'),
        user=os.getenv('CH_USER'),
        password=os.getenv('CH_PASSWORD')
    )
    CH_CLIENT.execute('SELECT 1')  # Test connection
    USE_CLICKHOUSE = True
except Exception as e:
    print(f"ClickHouse connection failed, using local storage: {e}")
    USE_CLICKHOUSE = False

def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'user_id' not in session:
            return jsonify({'error': 'Unauthorized'}), 401
        return f(*args, **kwargs)
    return decorated_function

def load_users():
    try:
        with open('users.json', 'r') as f:
            return json.load(f)
    except FileNotFoundError:
        return {}

@app.route('/')
def home():
    return redirect(url_for('login'))

@app.route('/record-data/login', methods=['GET', 'POST'])
def login():
    if request.method == 'GET':
        return render_template('login.html')
    
    data = request.get_json()
    password = data.get('password')
    users = load_users()
    
    for user_id, user_data in users.items():
        if user_data['password'] == password:
            session.permanent = True  # Make session permanent until logout
            session['user_id'] = user_id
            session['city'] = user_data.get('city')
            session['access'] = user_data.get('access')
            session['vehicle_type'] = user_data.get('vehicle_type')
            return jsonify({'success': True})
    
    return jsonify({'error': 'Invalid credentials'}), 401

@app.route('/logout')
def logout():
    session.clear()
    return redirect(url_for('login'))

@app.route('/api/routes')
@login_required
def get_routes():
    return jsonify(get_cached_routes())

@app.route('/api/stops')
@login_required
def get_stops():
    route_id = request.args.get('route_id')
    if not route_id:
        return jsonify({'error': 'route_id is required'}), 400
    return jsonify(get_cached_stops(route_id))

@app.route('/api/record', methods=['POST'])
@login_required
def record_stop():
    data = request.get_json()
    required_fields = ['route_id', 'stop_id', 'stop_name', 'lat', 'lon']
    
    if not all(field in data for field in required_fields):
        return jsonify({'error': 'Missing required fields'}), 400
    
    record = {
        'id': str(uuid.uuid4()),
        'route_id': data['route_id'],
        'stop_id': data['stop_id'],
        'stop_name': data['stop_name'],
        'latitude': data['lat'],
        'longitude': data['lon'],
        'user_id': session['user_id'],
        'timestamp': datetime.now().isoformat()
    }
    
    if USE_CLICKHOUSE:
        try:
            CH_CLIENT.execute(
                '''
                INSERT INTO stop_confirmations 
                (route_id, stop_id, stop_name, latitude, longitude, user_id, timestamp)
                VALUES
                ''',
                [(record['route_id'], record['stop_id'], record['stop_name'],
                  record['latitude'], record['longitude'], record['user_id'],
                  datetime.fromisoformat(record['timestamp']))]
            )
        except Exception as e:
            print(f"ClickHouse error, using local storage: {e}")
            local_data = load_local_data()
            local_data['stop_confirmations'].append(record)
            save_local_data(local_data)
    else:
        local_data = load_local_data()
        local_data['stop_confirmations'].append(record)
        save_local_data(local_data)
    
    return jsonify({'success': True})

@app.route('/record-data/bus-data')
def record_bus_data():
    return send_from_directory('static', 'index.html')

def fetch_routes_from_api():
    print("Fetching routes from new external API...")
    url = f'{API_BASE_URL}/api/route/list?city={API_CITY}&vehicle_type={API_VEHICLE_TYPE}'
    headers = {'X-Api-Token': API_TOKEN}
    try:
        response = requests.get(url, headers=headers, timeout=5)
        response.raise_for_status()
        return response.json()['routes']
    except Exception as e:
        logging.error(f"Failed to fetch routes from external API: {e}")
        return []  # or fallback to DUMMY_ROUTES

def fetch_stops_from_api(route_id):
    print(f"Fetching stops for route {route_id} from new external API...")
    url = f'{API_BASE_URL}/api/route/{route_id}?city={API_CITY}&vehicle_type={API_VEHICLE_TYPE}'
    headers = {'Authorization': f'Bearer {API_TOKEN}'}
    try:
        response = requests.get(url, headers=headers, timeout=5)
        response.raise_for_status()
        return response.json()['features']
    except Exception as e:
        logging.error(f"Failed to fetch stops for route {route_id} from external API: {e}")
        return []  # or fallback to DUMMY_STOPS.get(route_id, [])

def get_cached_routes():
    now = time.time()
    if (route_cache['routes'] is None or
        now - route_cache['routes_timestamp'] > CACHE_HOURS * 3600):
        # Fetch and cache
        raw_routes = fetch_routes_from_api()
        # Transform to expected structure
        route_cache['routes'] = [
            {
                'route_code': r['routeCode'],
                'route_name': r['routeName'],
                "route_end_point": r['routeEnd'],
                "route_start_point": r['routeStart']
            }
            for r in raw_routes
        ]
        route_cache['routes_timestamp'] = now
    return route_cache['routes']

def get_cached_stops(route_id):
    now = time.time()
    stops_entry = route_cache['stops'].get(route_id)
    if (not stops_entry or
        now - stops_entry['timestamp'] > CACHE_HOURS * 3600):
        # Fetch and cache
        raw_stops = fetch_stops_from_api(route_id)
        # Transform to expected structure
        stops_data = []
        for feature in raw_stops:
            if feature['geometry']['type'] == 'Point':
                coords = feature['geometry']['coordinates']
                props = feature['properties']
                stops_data.append({
                    'stop_id': props.get('Stop Code', ''),
                    'stop_name': props.get('Stop Name', ''),
                    'lat': coords[1],
                    'lon': coords[0]
                })
        route_cache['stops'][route_id] = {
            'data': stops_data,
            'timestamp': now
        }
    return route_cache['stops'][route_id]['data']

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=8000)
