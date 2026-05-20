#!/bin/bash
# Abu-Zahra Server Deployment Script
# Run this on your VPS/server

set -e

echo "=========================================="
echo "  Abu-Zahra Server v3.4 - Deployment"
echo "=========================================="

# Check Python
if ! command -v python3 &> /dev/null; then
    echo "[!] Python3 not found. Installing..."
    sudo apt update
    sudo apt install -y python3 python3-pip python3-venv
fi

# Create virtual environment
echo "[*] Creating virtual environment..."
python3 -m venv venv
source venv/bin/activate

# Install dependencies
echo "[*] Installing dependencies..."
pip install --upgrade pip
pip install -r requirements.txt

# SSL Setup
echo "[*] Setting up SSL with Let's Encrypt..."
if command -v certbot &> /dev/null; then
    sudo certbot certonly --standalone -d alsydyabwalzhra.online --non-interactive --agree-tos --email admin@alsydyabwalzhra.online 2>/dev/null || true
    sudo cp /etc/letsencrypt/live/alsydyabwalzhra.online/fullchain.pem cert.pem
    sudo cp /etc/letsencrypt/live/alsydyabwalzhra.online/privkey.pem key.pem
    sudo chown $USER:$USER cert.pem key.pem
    echo "[+] SSL certificates installed"
else
    echo "[!] certbot not found. Install with: sudo apt install certbot"
    echo "[*] Running without SSL (HTTP only)"
fi

# Setup systemd service
echo "[*] Setting up systemd service..."
sudo tee /etc/systemd/system/abuzahra.service > /dev/null <<EOF
[Unit]
Description=Abu-Zahra Admin Server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$(pwd)
Environment="PATH=$(pwd)/venv/bin"
ExecStart=$(pwd)/venv/bin/python3 server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable abuzahra
sudo systemctl start abuzahra

echo ""
echo "=========================================="
echo "  Deployment Complete!"
echo "=========================================="
echo ""
echo "  Server: https://alsydyabwalzhra.online:8443"
echo "  Dashboard: https://alsydyabwalzhra.online:8443/dashboard"
echo ""
echo "  Commands:"
echo "    sudo systemctl status abuzahra    - Check status"
echo "    sudo systemctl restart abuzahra  - Restart server"
echo "    sudo journalctl -u abuzahra -f   - View logs"
echo ""
echo "  To configure Firebase service account:"
echo "    export FIREBASE_PRIVATE_KEY_ID='your-key-id'"
echo "    export FIREBASE_PRIVATE_KEY='-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----'"
echo "    export FIREBASE_CLIENT_EMAIL='your-email@project.iam.gserviceaccount.com'"
echo ""
echo "=========================================="
