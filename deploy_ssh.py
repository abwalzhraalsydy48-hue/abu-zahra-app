#!/usr/bin/env python3
"""Full deployment of Abu-Zahra Server via SSH"""
import paramiko
import sys
import time
import os

HOST = "216.128.156.226"
USER = "root"
PASS = "E%t7SBQUAL2SE[kc"
LOCAL_DIR = "/home/z/my-project/download/AbuZahra-Admin"

def create_ssh():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASS, timeout=15)
    return client

def run_cmd(client, cmd, timeout=120):
    """Execute command and return output"""
    stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    code = stdout.channel.recv_exit_status()
    return out, err, code

def upload_file(client, local_path, remote_path):
    """Upload file via SFTP"""
    sftp = client.open_sftp()
    try:
        sftp.put(local_path, remote_path)
    finally:
        sftp.close()

def run_long_cmd(client, cmd, timeout=300):
    """Execute long-running command with progress"""
    transport = client.get_transport()
    channel = transport.open_session()
    channel.settimeout(timeout)
    channel.get_pty()
    channel.exec_command(cmd)

    output = ""
    while True:
        if channel.recv_ready():
            data = channel.recv(4096).decode('utf-8', errors='replace')
            output += data
            print(data, end='', flush=True)
        if channel.recv_stderr_ready():
            data = channel.recv_stderr(4096).decode('utf-8', errors='replace')
            output += data
            print(data, end='', flush=True)
        if channel.exit_status_ready():
            break
        time.sleep(0.1)

    code = channel.recv_exit_status()
    return output, code

def main():
    print("=" * 60)
    print("  Abu-Zahra Server v3.4 - Full Deployment")
    print("=" * 60)

    client = create_ssh()

    # Step 1: Check system
    print("\n[1/7] Checking system...")
    out, err, code = run_cmd(client, "uname -m && cat /etc/os-release | grep PRETTY_NAME && df -h / | tail -1 && free -h | grep Mem")
    print(f"  System: {out.strip().replace(chr(10), ' | ')}")

    # Step 2: Install dependencies
    print("\n[2/7] Installing system packages...")
    run_long_cmd(client, "apt-get update -qq && apt-get install -y -qq python3-pip python3-venv python3-dev gcc libffi-dev libssl-dev 2>&1 | tail -5", timeout=180)

    # Step 3: Create project directory and upload files
    print("\n[3/7] Uploading server files...")
    run_cmd(client, "mkdir -p /opt/abuzahra && rm -rf /opt/abuzahra/*")

    # Upload server.py
    upload_file(client, os.path.join(LOCAL_DIR, "server.py"), "/opt/abuzahra/server.py")
    print("  ✓ server.py uploaded")

    # Upload requirements.txt
    upload_file(client, os.path.join(LOCAL_DIR, "requirements.txt"), "/opt/abuzahra/requirements.txt")
    print("  ✓ requirements.txt uploaded")

    # Step 4: Create virtual environment and install Python packages
    print("\n[4/7] Installing Python packages...")
    run_long_cmd(client, "cd /opt/abuzahra && python3 -m venv venv && venv/bin/pip install --upgrade pip -q && venv/bin/pip install -r requirements.txt 2>&1 | tail -10", timeout=300)

    # Step 5: Check if port 8443 is available
    print("\n[5/7] Checking ports...")
    out, _, _ = run_cmd(client, "ss -tlnp | grep ':8443' || echo 'Port 8443 is free'")
    print(f"  {out.strip()}")

    # Check if port 80/443 are in use
    out, _, _ = run_cmd(client, "ss -tlnp | grep -E ':80 |:443 ' || echo 'Ports 80/443 are free'")

    # Step 6: Configure firewall
    print("\n[6/7] Configuring firewall...")
    run_cmd(client, "ufw allow 8443/tcp 2>/dev/null; ufw allow 80/tcp 2>/dev/null; ufw allow 443/tcp 2>/dev/null; echo 'Firewall configured'")

    # Step 7: Create systemd service
    print("\n[7/7] Setting up systemd service...")
    service_content = """[Unit]
Description=Abu-Zahra Admin Server v3.4
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/abuzahra
Environment="PATH=/opt/abuzahra/venv/bin:/usr/bin:/bin"
Environment="FIREBASE_PRIVATE_KEY_ID="
Environment="FIREBASE_PRIVATE_KEY="
Environment="FIREBASE_CLIENT_EMAIL="
ExecStart=/opt/abuzahra/venv/bin/python3 /opt/abuzahra/server.py
Restart=always
RestartSec=5
StandardOutput=append:/opt/abuzahra/server.log
StandardError=append:/opt/abuzahra/server.log

[Install]
WantedBy=multi-user.target
"""
    run_cmd(client, f"cat > /etc/systemd/system/abuzahra.service << 'SERVICEEOF'\n{service_content}SERVICEEOF")
    run_cmd(client, "systemctl daemon-reload && systemctl enable abuzahra")
    print("  ✓ Service installed and enabled")

    # Start the service
    print("\n Starting Abu-Zahra Server...")
    run_cmd(client, "systemctl start abuzahra")
    time.sleep(3)

    # Check status
    out, err, code = run_cmd(client, "systemctl is-active abuzahra")
    print(f"  Service status: {out.strip()}")

    if "active" in out:
        print("\n" + "=" * 60)
        print("  ✅ SERVER IS RUNNING!")
        print("=" * 60)
        print(f"  Server IP: {HOST}")
        print(f"  Dashboard: http://{HOST}:8443/dashboard")
        print(f"  API: http://{HOST}:8443/api/health")
        print(f"  Logs: journalctl -u abuzahra -f")
        print(f"  Restart: systemctl restart abuzahra")
        print("=" * 60)

        # Show recent logs
        print("\n  Recent logs:")
        logs, _, _ = run_cmd(client, "journalctl -u abuzahra --no-pager -n 20")
        for line in logs.strip().split('\n'):
            print(f"    {line}")
    else:
        print(f"\n  ❌ Service failed to start!")
        out, err, code = run_cmd(client, "journalctl -u abuzahra --no-pager -n 30")
        print(f"  Logs:\n{out}")
        if err:
            print(f"  Errors:\n{err}")

    client.close()

if __name__ == "__main__":
    main()
