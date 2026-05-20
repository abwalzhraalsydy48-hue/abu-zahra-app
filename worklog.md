---
Task ID: 1
Agent: Main Agent
Task: Comprehensive review and fix of Abu-Zahra bot server and Android app

Work Log:
- SSHed into server (216.128.156.226) via paramiko
- Discovered TWO bot services running: abu-zahra.service (old) and abuzahra.service (new)
- Stopped and disabled old abu-zahra.service
- Cleaned nginx config (removed duplicate blocks for alsydyabwalzhra.online)
- Discovered domain DNS points to WRONG IP: 198.54.117.242 (should be 216.128.156.226)
- Firebase RTDB returns "Permission denied" for public access (rules are locked down)
- Uploaded fixed server.py with /api/health endpoint
- Restarted abuzahra.service successfully
- Fixed Config.kt: changed SERVER_DOMAIN from https://alsydyabwalzhra.online to http://216.128.156.226:8443
- Fixed LinkActivity.kt: added server connectivity test, proper error messages for JSON parse errors
- Fixed FirebaseManager.kt: removed non-existent removeValueDelayed() method that was causing crashes
- Fixed MainActivity.kt: added comprehensive runtime permissions, auto-request on launch
- Added all missing permissions: POST_NOTIFICATIONS, ACCESS_BACKGROUND_LOCATION, READ_MEDIA_*
- Pushed all fixes to GitHub

Stage Summary:
- Server is running and healthy (health endpoint returns OK)
- Register endpoint returns proper JSON responses
- Old bot completely stopped and disabled
- Android app configured to use direct IP instead of broken domain
- All source files pushed to GitHub at https://github.com/abwalzhraalsydy48-hue/Abu-Zahra-App.git
- APK needs to be rebuilt by user in Android Studio (no Android SDK on build machine)
