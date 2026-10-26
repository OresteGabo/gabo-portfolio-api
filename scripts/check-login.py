#!/usr/bin/env python3
"""Interactively verify local login/session/logout without displaying credentials."""
import getpass
import http.cookiejar
import json
from pathlib import Path
import shlex
import urllib.error
import urllib.request

origin = 'http://localhost:8080'
email = ''
env_file = Path(__file__).resolve().parent.parent / '.env'
if env_file.exists():
    for raw in env_file.read_text().splitlines():
        key, separator, value = raw.strip().removeprefix('export ').partition('=')
        if separator and key.strip() == 'BONDS_ADMIN_EMAIL':
            email = ' '.join(shlex.split(value, comments=True))
if not email:
    email = input('Owner email: ').strip()
password = getpass.getpass('Your original owner password: ')
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(path, body=None):
    payload = None if body is None else json.dumps(body).encode()
    headers = {'Origin': origin}
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    call = urllib.request.Request(origin + '/api/bonds/auth/' + path, data=payload, headers=headers)
    with client.open(call, timeout=15) as response:
        return json.load(response)

try:
    login = request('login', {'email': email, 'password': password})
    password = None
    if not login.get('authenticated'):
        raise SystemExit('Sign-in was not confirmed.')
    try:
        if not request('session').get('authenticated'):
            raise SystemExit('The login succeeded, but session verification failed.')
        print('Owner sign-in and session verification passed.')
    finally:
        request('logout', {})
        print('The test session has been signed out.')
except urllib.error.HTTPError as error:
    message = {401: 'Email or password was not accepted.', 403: 'Check the local API origin configuration.', 429: 'Too many attempts; try again in 15 minutes.'}.get(error.code, 'Check the API configuration and try again.')
    raise SystemExit(f'HTTP {error.code}: {message}') from None
except urllib.error.URLError:
    raise SystemExit('The local API is not reachable. Start it before testing login.') from None
