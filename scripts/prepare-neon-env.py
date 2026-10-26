#!/usr/bin/env python3
"""Map Neon CLI env vars to Spring JDBC settings without printing credentials."""
from pathlib import Path
import os
import shlex
from urllib.parse import urlsplit, unquote, urlencode

root = Path(__file__).resolve().parent.parent
values = {}
for raw in (root / '.env').read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    key, sep, value = line.removeprefix('export ').partition('=')
    if sep:
        values[key.strip()] = ' '.join(shlex.split(value, comments=True))

def parse(key):
    uri = urlsplit(values.get(key, ''))
    if uri.scheme not in ('postgres', 'postgresql') or not (uri.hostname or '').endswith('.neon.tech'):
        raise SystemExit(f'{key} must be a Neon PostgreSQL connection string.')
    if not uri.username or not uri.password or not uri.path.strip('/'):
        raise SystemExit(f'{key} is missing database credentials.')
    return uri

pooled = parse('DATABASE_URL')
direct = parse('DATABASE_URL_UNPOOLED')
if '-pooler' in direct.hostname:
    raise SystemExit('Migrations require the unpooled host.')
if (pooled.username, pooled.password, pooled.path) != (direct.username, direct.password, direct.path):
    raise SystemExit('Pooled and direct credentials must refer to the same database.')

def jdbc(uri):
    return f'jdbc:postgresql://{uri.hostname}:{uri.port or 5432}{uri.path}?' + urlencode({'sslmode': 'verify-full', 'sslfactory': 'org.postgresql.ssl.DefaultJavaSSLFactory'})

result = {k: v for k, v in values.items() if k.startswith('BONDS_') or k in ('CRON_SECRET',)}
result.update({
    'SPRING_PROFILES_ACTIVE': 'cloudrun',
    'GABO_DATABASE_URL': jdbc(pooled),
    'GABO_MIGRATION_DATABASE_URL': jdbc(direct),
    'GABO_DATABASE_USER': unquote(pooled.username),
    'GABO_DATABASE_PASSWORD': unquote(pooled.password),
    'GABO_ALLOWED_ORIGINS': 'https://bonds.orestegabo.dev',
})
target = root / '.env.cloudrun'
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w') as out:
    out.write('# Private Cloud Run configuration. Add the deployed API origin before mobile login.\n')
    for key, value in result.items():
        out.write(key + '=' + shlex.quote(value) + '\n')
os.chmod(target, 0o600)
print('Prepared .env.cloudrun: TLS-verified JDBC, pooled queries, direct migrations. Credentials hidden.')
