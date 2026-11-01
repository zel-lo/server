#!/usr/bin/env python3
"""Validate production configuration without printing any secret values."""
import base64
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parent
image_env = Path(sys.argv[1]) if len(sys.argv) > 1 else root / '.images.env'
for path in (root / '.env', image_env):
    if not path.is_file():
        sys.exit(f'Missing {path.name}; run init-env.sh first.')
    if path.stat().st_mode & 0o077:
        sys.exit(f'{path.name} must have mode 600.')

result = subprocess.run([
    'docker', 'compose', '--project-directory', str(root),
    '--env-file', str(root / '.env'), '--env-file', str(image_env),
    '-f', str(root / 'compose.yaml'), 'config', '--format', 'json',
], capture_output=True, text=True)
if result.returncode:
    missing = re.findall(r'required variable ([A-Z_]+) is missing', result.stderr)
    if missing:
        sys.exit('Fill ' + ', '.join(sorted(set(missing))) + ' in .env.')
    sys.exit('Configuration is incomplete. Fill MYSQL_PASSWORD, MYSQL_ROOT_PASSWORD, '
             'SESSION_ENCRYPTION_KEY, GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET in .env.')

config = json.loads(result.stdout)
backend = config['services']['server']['environment']
database = config['services']['mysql']['environment']
required = {
    'MYSQL_PASSWORD': database['MYSQL_PASSWORD'],
    'MYSQL_ROOT_PASSWORD': database['MYSQL_ROOT_PASSWORD'],
    'SESSION_ENCRYPTION_KEY': backend['SESSION_ENCRYPTION_KEY'],
    'GITHUB_CLIENT_ID': backend['GITHUB_CLIENT_ID'],
    'GITHUB_CLIENT_SECRET': backend['GITHUB_CLIENT_SECRET'],
}
for key, value in required.items():
    if not value or str(value).lower().startswith(('change_me', 'your-', 'replace-')):
        sys.exit(f'Fill {key} in .env.')
try:
    valid_key = len(base64.b64decode(backend['SESSION_ENCRYPTION_KEY'], validate=True)) == 32
except (ValueError, TypeError):
    valid_key = False
if not valid_key:
    sys.exit('SESSION_ENCRYPTION_KEY must be a base64-encoded 32-byte key.')
print('Production environment validated; secret values are hidden.')
