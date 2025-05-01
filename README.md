# 🚌 Bus Route Tracker

![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)
![Python](https://img.shields.io/badge/python-3.10%2B-blue)
![Dockerized](https://img.shields.io/badge/docker-ready-blue)

Welcome to **Bus Route Tracker** — your modern, open-source companion for collecting, confirming, and managing bus stop and route data! Whether you're a field team, researcher, or open mobility enthusiast, this tool is designed to make your data collection journey smooth, beautiful, and efficient.


<p align="center">
  <img src="android_route_tracker/app/src/main/res/drawable/icon.png" alt="Bus Route Tracker Icon" width="180"/>
  <br>
</p>


## 📚 Table of Contents
- [✨ Features](#-features)
- [🚀 Quick Start](#-quick-start)
- [🐳 Docker](#-docker)
- [🌐 API Endpoints](#-api-endpoints)
- [🎨 UI Highlights](#-ui-highlights)
- [🤝 Contributing](#-contributing)
- [🛡️ License](#-license)
- [🙋‍♂️ About the Author](#-about-the-author)

## ✨ Features

- 🔒 **Secure Login**: Only authorized users can access and record data.
- 🔍 **Route & Stop Search**: Fast, fuzzy search for routes and stops.
- 📍 **Live GPS**: See your current location and accuracy in real time.
- 📝 **Stop Confirmation**: Record and confirm stops with a single tap.
- 📴 **Offline Fallback**: Local data storage if the database is unavailable.
- 📱 **Modern UI**: Mobile-first, beautiful gradients, and smooth interactions.
- 🔗 **API Integration**: Fetches live route/stop data from a backend API.
- 🐳 **Dockerized**: Easy to run anywhere, from your laptop to the cloud.
- 🛠️ **Admin APIs**: Endpoints for routes, stops, and data recording.
- 🌏 **Customizable**: Easily adapt for other cities, vehicle types, or data sources.

## 🚀 Quick Start

Ready to get rolling? Follow these steps:

1. **Clone the repository**

   ```bash
   git clone https://github.com/nammayatri/bus-route-tracker.git
   cd bus-route-tracker
   ```

2. **Install dependencies**

   ```bash
   pip install -r requirements.txt
   ```

3. **Configure your environment**

   ```bash
   cp config/config.json.example config/config.json
   # Edit config/config.json with your API keys, DB credentials, etc.
   ```

4. **Run the app**

   ```bash
   python app.py
   ```

   Open your browser and visit [http://localhost:8000](http://localhost:8000) to start tracking!

## 🐳 Docker

Want to run in a container? No problem!

```bash
docker build -t bus-stop-locator .
docker run -p 8000:8000 bus-stop-locator
```

Or use the Makefile for advanced build/push:

```bash
make build
make push-prod
make push-sandbox
```

## 🌐 API Endpoints

- `POST /routeTrackerApi/login` — User login
- `GET /routeTrackerApi/routes` — List all routes
- `GET /routeTrackerApi/stops?route_id=...` — List stops for a route
- `POST /routeTrackerApi/record` — Record a stop confirmation
- `POST /routeTrackerApi/location-update` — Update bus location

All endpoints (except login) require authentication.

## 🎨 UI Highlights

- 📱 **Mobile-first**: Optimized for data collection on the go.
- 🧩 **Custom dropdowns**: Fast search and selection for routes and stops.
- 📡 **Live GPS**: See your current location and accuracy.
- ✅ **Confirmation modals**: Prevent accidental submissions.
- 🌈 **Modern look**: Smooth gradients, rounded corners, and subtle shadows.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome!  
Feel free to open an issue or submit a pull request.

## 🛡️ License

This project is licensed under the [MIT License](LICENSE).

## 🙋‍♂️ About the Author

Made with ❤️ by [vijaygupta18](https://github.com/vijaygupta18)

Reference: [Namma Yatri Bus Route Tracker](https://github.com/nammayatri/bus-route-tracker) 