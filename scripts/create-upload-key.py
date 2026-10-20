#!/usr/bin/env python3
"""One-time, explicitly authorized Roomote upload-key creation. Never prints secrets."""
import os
import secrets
import subprocess
from pathlib import Path

directory = Path.home() / ".android-dev/secrets/com.elvi.tvremote"
keystore = directory / "upload.jks"
settings = directory / "signing.env"
if keystore.exists() or settings.exists():
    raise SystemExit("Existing signing material found; refusing to overwrite it.")
directory.mkdir(mode=0o700, parents=True, exist_ok=True)
directory.chmod(0o700)
password = secrets.token_urlsafe(48)
environment = os.environ.copy()
environment["ROOMOTE_NEW_KEY_PASSWORD"] = password
result = subprocess.run([
    "keytool", "-genkeypair", "-noprompt", "-storetype", "JKS",
    "-keystore", str(keystore), "-alias", "upload",
    "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
    "-dname", "CN=Santosh A",
    "-storepass:env", "ROOMOTE_NEW_KEY_PASSWORD",
    "-keypass:env", "ROOMOTE_NEW_KEY_PASSWORD",
], env=environment, capture_output=True, text=True)
if result.returncode:
    raise SystemExit("Key creation failed. Inspect the private signing directory before retrying.")
keystore.chmod(0o600)
content = (
    f"ANDROID_KEYSTORE_FILE={keystore}\n"
    f"ANDROID_KEYSTORE_PASSWORD={password}\n"
    "ANDROID_KEY_ALIAS=upload\n"
    f"ANDROID_KEY_PASSWORD={password}\n"
)
fd = os.open(settings, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w") as stream:
    stream.write(content)
print("Created Roomote upload key and private signing configuration. Preserve both files.")
