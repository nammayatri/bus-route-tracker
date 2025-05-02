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
import base64

# Centralized config loading
CONFIG_FILE = os.path.abspath(os.path.join(os.path.dirname(__file__), "config", "config.json"))
CONFIG_ENV_DATA = os.getenv("CONFIG_ENV_DATA")

def load_config():
    """Load config.json and return it as a dictionary."""
    print(f"Loading config from {CONFIG_FILE}")
    try:
        if CONFIG_ENV_DATA:
            decoded_config = base64.b64decode(CONFIG_ENV_DATA).decode("utf-8")
            config = json.loads(decoded_config)
            print(f"✅ Loaded config from environment variable\n")
            return config
        else:
            with open(CONFIG_FILE, "r") as file:
                config = json.load(file)
            print(f"✅ Loaded config from {CONFIG_FILE}\n")
            return config
    except FileNotFoundError:
        print(f"❌ ERROR: Missing configuration file: {CONFIG_FILE}")
        exit(1)
    except json.JSONDecodeError:
        print(f"❌ ERROR: Invalid JSON format in {CONFIG_FILE}")
        exit(1)

config = load_config()

app = Flask(__name__)
app.secret_key = config["SECRET_KEY"]
app.permanent_session_lifetime = timedelta(days=365)  # Effectively permanent

# File-based storage for local testing
LOCAL_DATA_FILE = "local_data.json"

# Configurable cache duration (in hours)
CACHE_HOURS = int(config.get('ROUTE_CACHE_HOURS', 1))  # Default: 1 hour

# In-memory cache
route_cache = {
    'routes': None,
    'routes_timestamp': 0,
    'stops': {},  # route_id: {'data': ..., 'timestamp': ...}
}

API_BASE_URL = config.get('API_BASE_URL', 'http://localhost:8090')
API_TOKEN = config.get('API_TOKEN', 'test')
API_CITY = config.get('API_CITY', 'chennai')
API_VEHICLE_TYPE = config.get('API_VEHICLE_TYPE', 'bus')
USE_CLICKHOUSE = config.get('USE_CLICKHOUSE', False)
CLICKHOUSE_DB = config.get('CH_DB', 'default')
SESSION_API_TOKEN = config.get('SESSION_API_TOKEN', 'some_random_token')

def load_local_data():
    try:
        with open(LOCAL_DATA_FILE, 'r') as f:
            content = f.read().strip()
            if not content:
                return {"stop_confirmations": [], "location_updates": []}
            return json.loads(content)
    except (FileNotFoundError, json.JSONDecodeError):
        return {"stop_confirmations": [], "location_updates": []}

def save_local_data(data):
    with open(LOCAL_DATA_FILE, 'w') as f:
        json.dump(data, f, indent=2)

# Database configurations with fallback
# try:
#     PG_CONFIG = {
#         'dbname': 'mtc_replica',
#         'user': os.getenv('DB_USER'),
#         'password': os.getenv('DB_PASSWORD'),
#         'host': os.getenv('DB_HOST', 'localhost')
#     }
#     conn = psycopg2.connect(**PG_CONFIG)
#     conn.close()
#     USE_DATABASE = True
# except Exception as e:
#     print(f"Database connection failed, using local storage: {e}")
#     USE_DATABASE = False

def get_clickhouse_client():
    return Client(
        host=config.get('CH_HOST', 'localhost'),
        user=config.get('CH_USER', 'default'),
        password=config.get('CH_PASSWORD', 'root'),
        database=config.get('CH_DB', 'default')
    )

def token_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        token = request.headers.get('Session-Api-Token')
        if not token or token != SESSION_API_TOKEN:
            return jsonify({'error': 'Unauthorized'}), 401
        return f(*args, **kwargs)
    return decorated_function

@app.route('/routeTrackerApi/routes')
@token_required
def get_routes():
    return jsonify(get_cached_routes())

@app.route('/routeTrackerApi/stops')
@token_required
def get_stops():
    route_id = request.args.get('route_id')
    if not route_id:
        return jsonify({'error': 'route_id is required'}), 400
    return jsonify(get_cached_stops(route_id))

@app.route('/routeTrackerApi/record', methods=['POST'])
@token_required
def record_stop():
    data = request.get_json()
    required_fields = ['route_id', 'stop_id', 'stop_name', 'lat', 'lon']
    
    if not all(field in data for field in required_fields):
        return jsonify({'error': 'Missing required fields'}), 400
    
    record_type = data.get('type', 'STOP_RECORD')
    record = {
        'id': str(uuid.uuid4()),
        'route_id': data['route_id'],
        'stop_id': data['stop_id'],
        'stop_name': data['stop_name'],
        'latitude': data['lat'],
        'longitude': data['lon'],
        'timestamp': datetime.now().isoformat(),
        'type': record_type,
        'device_id': data.get('device_id'),
        'device_name': data.get('device_name'),
        'app_version': data.get('app_version')
    }
    
    if USE_CLICKHOUSE:
        try:
            client = get_clickhouse_client()
            client.execute(
                f'''
                INSERT INTO {CLICKHOUSE_DB}.bus_stop_location_data 
                (id, route_id, stop_id, stop_name, latitude, longitude, timestamp, type, device_id, device_name, app_version)
                VALUES
                ''',
                [(
                    record['id'],
                    record['route_id'],
                    record['stop_id'],
                    record['stop_name'],
                    record['latitude'] if record['latitude'] is not None else 0.0,
                    record['longitude'] if record['longitude'] is not None else 0.0,
                    datetime.fromisoformat(record['timestamp'].replace('Z', '+00:00')),
                    record['type'] or '',
                    record.get('device_id'),
                    record.get('device_name'),
                    record.get('app_version')
                )]
            )
            print(f"Stop record: {record['stop_id']}, {record['stop_name']} at {record['timestamp']} (type={record['type']}) device_id={record.get('device_id')} device_name={record.get('device_name')} app_version={record.get('app_version')}")
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

@app.route('/routeTrackerApi/bus-data')
def record_bus_data():
    return send_from_directory('static', 'index.html')

@app.route('/routeTrackerApi/location-update', methods=['POST'])
@token_required
def location_update():
    data = request.get_json()
    log_entry = {
        'id': str(uuid.uuid4()),
        'route_id': data.get('route_id'),
        'stop_id': data.get('stop_id'),
        'stop_name': data.get('stop_name'),
        'latitude': data.get('lat'),
        'longitude': data.get('lon'),
        'timestamp': data.get('timestamp'),
        'type': 'LOCATION_RECORD',
        'device_id': data.get('device_id'),
        'device_name': data.get('device_name'),
        'app_version': data.get('app_version')
    }
    if USE_CLICKHOUSE:
        try:
            client = get_clickhouse_client()
            client.execute(
                f'''
                INSERT INTO {CLICKHOUSE_DB}.bus_stop_location_data 
                (id, route_id, stop_id, stop_name, latitude, longitude, timestamp, type, device_id, device_name, app_version)
                VALUES
                ''',
                [(
                    log_entry['id'],
                    log_entry['route_id'] or '',
                    log_entry['stop_id'] or '',
                    log_entry['stop_name'] or '',
                    log_entry['latitude'] if log_entry['latitude'] is not None else 0.0,
                    log_entry['longitude'] if log_entry['longitude'] is not None else 0.0,
                    datetime.fromisoformat(log_entry['timestamp'].replace('Z', '+00:00')),
                    log_entry['type'] or '',
                    log_entry.get('device_id'),
                    log_entry.get('device_name'),
                    log_entry.get('app_version')
                )]
            )
            print(f"Location update: {data.get('lat')}, {data.get('lon')} at {data.get('timestamp')} device_id={log_entry.get('device_id')} device_name={log_entry.get('device_name')} app_version={log_entry.get('app_version')}")
        except Exception as e:
            print(f"ClickHouse error, using local storage: {e}")
            local_data = load_local_data()
            if 'location_updates' not in local_data:
                local_data['location_updates'] = []
            local_data['location_updates'].append(log_entry)
            save_local_data(local_data)
    else:
        local_data = load_local_data()
        if 'location_updates' not in local_data:
            local_data['location_updates'] = []
        local_data['location_updates'].append(log_entry)
        save_local_data(local_data)
    print(f"Location update: {data.get('lat')}, {data.get('lon')} at {data.get('timestamp')}")
    return jsonify({'success': True})

def fetch_routes_from_api():
    print("Fetching routes from new external API...")
    url = f'{API_BASE_URL}/datatool/api/route/list?city={API_CITY}&vehicle_type={API_VEHICLE_TYPE}'
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
    url = f'{API_BASE_URL}/datatool/api/route/{route_id}?city={API_CITY}&vehicle_type={API_VEHICLE_TYPE}'
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

@app.route('/routeTrackerApi/configs/<city>/<vehicle_type>')
def get_configs(city, vehicle_type):
    return jsonify(config.get(city, {}).get(vehicle_type, {}))


if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=8000)
