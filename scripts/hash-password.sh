#!/bin/sh
# Password input remains hidden and never appears in arguments or shell history.
exec python3 - <<'PY'
import getpass, hashlib, secrets
password = getpass.getpass('Password (15–128 characters): ')
if not 15 <= len(password) <= 128:
    raise SystemExit('Use a password between 15 and 128 characters.')
salt = secrets.token_bytes(16)
key = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000, 32)
print(f'pbkdf2-sha256:600000:{salt.hex()}:{key.hex()}')
PY
