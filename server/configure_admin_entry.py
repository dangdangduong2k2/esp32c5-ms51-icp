"""Add admin entry redirects to this VPS's existing MS51 HTTPS virtual host."""
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def main():
    site = Path('/etc/nginx/sites-available/ms51-updates-v2')
    original = site.read_text()
    assert original.count('server_name 142-91-99-63.sslip.io;') == 1
    assert 'location /ms51/connect/v1/' in original
    marker = '    location / { return 404; }'
    assert original.count(marker) == 1
    redirects = ''.join(
        '    location = ' + path + ' { return 302 /ms51/connect/v1/admin/; }\n'
        for path in ('/', '/admin', '/admin/')
    )
    if redirects in original:
        print('Admin entry redirects already configured.')
        return
    # Refuse to overwrite another configured home/admin route.
    for path in ('/', '/admin', '/admin/'):
        assert 'location = ' + path + ' ' not in original
    backup = site.with_name(site.name + '.before-admin-entry-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    with backup.open('x') as saved:
        saved.write(original)
    try:
        site.write_text(original.replace(marker, redirects + marker))
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    except BaseException:
        site.write_text(original)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
        raise
    print('MS51 home and /admin now redirect to the authenticated admin console.')


if __name__ == '__main__':
    main()
