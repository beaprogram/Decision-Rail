#!/usr/bin/env python3
"""Live acceptance checks for a DecisionRail instance deployed per deploy/render/README.md.

Runs against the real public HTTPS host and checks what that guide says must hold before the
instance is described as ready: the running revision, the edge's headers and route allowlist, the
separation of the session and Basic chains, one full synthetic payment with its consumer receipts,
and the per-address authentication limiter against spoofed forwarding headers.

Credentials are read from a file of KEY=value lines that stays outside the repository (the guide's
environment sheet). Nothing read from it is ever printed; failures report status codes and names.
This harness only exercises a deployment - it never changes one, and it must not be pointed at the
development stack.

  python3 deploy/render/live-check.py --base https://<service>.onrender.com --env <env-file> \
      [--phase all|readonly|session|flow|limiter|cold] [--expect-commit <sha>] [--pg-ca <pem>]

      readonly : info/readiness/security headers/route allowlist/body limit/actuator exposure
      session  : identity, CSRF, cookie flags, session vs /v1 separation, logout
      flow     : visitor authorize/capture/refund/reconciliation/replay, then receipts via psql
      limiter  : forwarding-header spoofing vs the limiter - LOCKS THE CALLING ADDRESS FOR THE WINDOW,
                 so run it last, and confirm separately that a different client is unaffected
      cold     : time the first answer after an idle period (free instances sleep)
      all      : readonly, session, flow, limiter in that order

The receipt check needs psql and the provider's CA; --pg-exec substitutes a psql command prefix for a
rehearsal against a disposable database, and --insecure accepts a locally issued certificate there.
Results are written to live-check-<utc>-<phase>.json beside the environment file's directory.
"""
import argparse, base64, http.client, json, os, re, shutil, ssl, subprocess, sys, time, uuid
from http.cookies import SimpleCookie
from pathlib import Path
from urllib.parse import urlencode, urlparse

ACCOUNT = 'aaaa0001-0000-4000-8000-000000000001'
results, cookies = [], {}


def load_env(path):
    env = {}
    for line in Path(path).read_text().splitlines():
        m = re.match(r'^([A-Z_]+)=(.*)$', line)
        if m:
            env[m.group(1)] = m.group(2)
    return env


def record(name, ok, detail=''):
    results.append({'check': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + (': ' + detail if detail else ''), flush=True)
    if not ok:
        raise SystemExit(f'stopped at failed check: {name}')


class Client:
    def __init__(self, base, insecure=False):
        u = urlparse(base)
        assert u.scheme == 'https', 'the live host must be https'
        self.host, self.port = u.hostname, u.port or 443
        self.ctx = ssl.create_default_context()  # real certificate validation
        if insecure:  # local rehearsal behind a "tls internal" certificate only
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE

    def req(self, method, path, body=None, user=None, headers=None, jar=False, timeout=60):
        h = {'Accept': 'application/json'}
        if user:
            h['Authorization'] = 'Basic ' + base64.b64encode(user.encode()).decode()
        if jar and cookies:
            h['Cookie'] = '; '.join(k + '=' + v for k, v in cookies.items())
        if isinstance(body, dict):
            body = json.dumps(body)
            h['Content-Type'] = 'application/json'
        if headers:
            h.update(headers)
        c = http.client.HTTPSConnection(self.host, self.port, timeout=timeout, context=self.ctx)
        t0 = time.monotonic()
        try:
            c.request(method, path, body=body, headers=h)
            r = c.getresponse()
            data = r.read()
            rh = r.getheaders()
        finally:
            c.close()
        elapsed = round(time.monotonic() - t0, 3)
        if jar:
            for k, v in rh:
                if k.lower() == 'set-cookie':
                    cs = SimpleCookie()
                    cs.load(v)
                    for n, m in cs.items():
                        cookies[n] = m.value
        try:
            parsed = json.loads(data)
        except Exception:
            parsed = data
        return r.status, parsed, dict((k.lower(), v) for k, v in rh), rh, elapsed


def phase_readonly(c, expect_commit):
    st, info, _, _, el = c.req('GET', '/actuator/info')
    info = info.get('decisionrail', {}) if isinstance(info, dict) else {}
    record('info reports the pinned commit', st == 200 and info.get('commit') == expect_commit,
           f'commit={str(info.get("commit"))[:7]} migration={info.get("latestMigration")} {el}s')
    record('schema reached V15', info.get('latestMigration') == 'V15')
    record('instance declares itself synthetic', info.get('synthetic') is True)
    st, body, _, _, el = c.req('GET', '/actuator/health/readiness')
    record('readiness 200', st == 200, f'{el}s')
    st, _, h, _, _ = c.req('GET', '/dashboard/', headers={'Accept': 'text/html'})
    record('dashboard served over HTTPS', st == 200 and 'text/html' in h.get('content-type', ''))
    for name, want in [('strict-transport-security', 'max-age=31536000'), ('x-content-type-options', 'nosniff'),
                       ('x-frame-options', 'DENY'), ('referrer-policy', 'no-referrer'),
                       ('content-security-policy', "default-src 'self'")]:
        record(f'security header {name}', want in h.get(name, ''), h.get(name, '<absent>')[:60])
    record('Server header removed', 'server' not in h or 'caddy' not in h.get('server', '').lower(), h.get('server', '<absent>'))
    st, _, _, _, _ = c.req('GET', '/actuator/prometheus')
    record('/actuator/prometheus is 404', st == 404, f'status={st}')
    for p in ['/actuator/health/async', '/actuator/health/async/asyncDelivery', '/actuator/health/async/x/y']:
        st, _, _, _, _ = c.req('GET', p)
        record(f'anonymous {p} is 401', st == 401, f'status={st}')
    st, _, _, _, _ = c.req('GET', '/actuator/env')
    record('/actuator/env not routed (404)', st == 404, f'status={st}')
    st, _, _, _, _ = c.req('POST', '/v1/payments/authorizations', 'x' * 64001, headers={'Content-Type': 'application/json'})
    record('64001-byte body refused with 413', st == 413, f'status={st}')
    st, body, h, _, _ = c.req('GET', '/v1/does-not-exist')
    record('unknown /v1 path stays an error, not HTML 200', st in (401, 404) and 'text/html' not in h.get('content-type', ''), f'status={st}')
    st, body, h, _, _ = c.req('GET', '/ui/does-not-exist')
    record('unknown /ui path stays an error, not HTML 200', st in (401, 404) and 'text/html' not in h.get('content-type', ''), f'status={st}')
    st, _, h, _, _ = c.req('GET', '/dashboard/payments/anything', headers={'Accept': 'text/html'})
    record('single-page fallback scoped to /dashboard', st == 200 and 'text/html' in h.get('content-type', ''), f'status={st}')
    st, _, _, _, _ = c.req('GET', '/not-on-the-allowlist')
    record('path outside the edge allowlist is 404', st == 404, f'status={st}')


def phase_session(c, env):
    cookies.clear()
    st, identity, _, _, _ = c.req('GET', '/ui/identity', jar=True)
    demo = identity.get('publicDemo') if isinstance(identity, dict) else None
    record('anonymous identity announces the public demo', st == 200 and isinstance(demo, dict) and demo.get('sharedState') is True,
           f'visitor={demo.get("visitorUsername") if demo else None} commandsPerMinute={demo.get("commandsPerMinute") if demo else None}')
    form = urlencode({'username': 'visitor', 'password': env['VISITOR_PASSWORD']})
    st, _, _, _, _ = c.req('POST', '/ui/session', form, headers={'Content-Type': 'application/x-www-form-urlencoded'}, jar=True)
    record('sign-in without CSRF token is 403', st == 403, f'status={st}')
    st, identity, _, raw, _ = c.req('POST', '/ui/session', form, headers={'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN', '')}, jar=True)
    session = [v for k, v in raw if k.lower() == 'set-cookie' and v.startswith('JSESSIONID=')]
    record('visitor sign-in 200', st == 200, f'status={st}')
    record('session cookie is Secure, HttpOnly, SameSite', bool(session) and all(x in session[0] for x in ('Secure', 'HttpOnly', 'SameSite')),
           re.sub(r'JSESSIONID=[^;]+', 'JSESSIONID=<redacted>', session[0]) if session else '<no JSESSIONID>')
    st, _, _, _, _ = c.req('GET', '/ui/accounts', jar=True)
    record('session reads /ui/accounts', st == 200, f'status={st}')
    st, _, _, _, _ = c.req('GET', f'/v1/accounts/{ACCOUNT}', jar=True)
    record('session buys nothing on /v1 (401)', st == 401, f'status={st}')
    st, _, _, _, _ = c.req('DELETE', '/ui/session', headers={'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN', '')}, jar=True)
    record('logout 204', st == 204, f'status={st}')
    st, _, _, _, _ = c.req('GET', '/ui/accounts', jar=True)
    record('session invalidated after logout (401)', st == 401, f'status={st}')


def phase_flow(c, env, pg_ca, pg_exec=None):
    visitor = 'visitor:' + env['VISITOR_PASSWORD']
    admin = 'admin:' + env['ADMIN_PASSWORD']
    policy = {'versionId': 'render-live-policy', 'definition': {'rules': [{'code': 'RENDER_LIVE_AMOUNT', 'description': 'Live check fixture',
              'scoreContribution': 60, 'flag': 'HIGH_AMOUNT', 'terminal': False, 'expression': {'operator': 'AMOUNT_AT_LEAST', 'amountMinor': 2000}}]}}
    st, _, _, _, _ = c.req('POST', '/v1/policies', policy, user=admin)
    record('candidate policy registered (admin)', st in (200, 201), f'status={st}')
    st, _, _, _, _ = c.req('PUT', '/v1/ops/shadow', {'enabled': True, 'candidateVersion': 'render-live-policy'}, user=admin)
    record('shadow evaluation enabled for the candidate', st == 200, f'status={st}')
    key = 'live-' + str(uuid.uuid4())
    body = {'accountId': ACCOUNT, 'amountMinor': 2500, 'currency': 'CAD', 'country': 'CA'}
    st, p, _, _, el = c.req('POST', '/v1/payments/authorizations', body, user=visitor, headers={'Idempotency-Key': key})
    record('visitor authorization 201', st == 201, f'{el}s')
    payment = p['id']
    st, p2, _, _, _ = c.req('POST', '/v1/payments/authorizations', body, user=visitor, headers={'Idempotency-Key': key})
    record('idempotent repeat returns the same payment', st == 201 and p2['id'] == payment)
    st, _, _, _, _ = c.req('POST', f'/v1/payments/{payment}/capture', user=visitor, headers={'Idempotency-Key': key + '-capture'})
    record('capture 200', st == 200, f'status={st}')
    st, _, _, _, _ = c.req('POST', f'/v1/payments/{payment}/refunds', {'amountMinor': 500, 'reason': 'live acceptance check'}, user=visitor, headers={'Idempotency-Key': key + '-refund'})
    record('partial refund 201', st == 201, f'status={st}')
    st, report, _, _, el = c.req('GET', '/v1/reconciliation', user=visitor)
    record('reconciliation CLEAN', st == 200 and report.get('status') == 'CLEAN', f'status={report.get("status") if isinstance(report, dict) else st} {el}s')
    st, job, _, _, _ = c.req('POST', '/v1/replay-jobs', {'candidateVersion': 'render-live-policy', 'limit': 100}, user=visitor, headers={'Idempotency-Key': key + '-replay'})
    record('replay job admitted within budget via /v1', st == 201, f'status={st}')
    deadline = time.monotonic() + 180
    status = None
    while time.monotonic() < deadline:
        st, r, _, _, _ = c.req('GET', '/v1/replay-jobs/' + job['id'], user=visitor)
        status = r.get('status')
        if status in ('COMPLETED', 'FAILED'):
            break
        time.sleep(2)
    record('replay completed', status == 'COMPLETED', f'status={status}')
    cookies.clear()
    c.req('GET', '/ui/identity', jar=True)
    c.req('POST', '/ui/session', urlencode({'username': 'visitor', 'password': env['VISITOR_PASSWORD']}),
          headers={'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN', '')}, jar=True)
    st, r, _, _, _ = c.req('POST', '/ui/replay-jobs', {'candidateVersion': 'render-live-policy', 'limit': 100},
                           headers={'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN', ''), 'Idempotency-Key': key + '-replay-ui'}, jar=True)
    record('replay through /ui is budgeted by the same interceptor (201 admitted or 429 refused)', st in (201, 429), f'status={st}')
    c.req('DELETE', '/ui/session', headers={'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN', '')}, jar=True)
    receipts(env, pg_ca, payment, pg_exec)


def receipts(env, pg_ca, payment, pg_exec=None):
    if pg_exec:  # rehearsal only: a command prefix that already is a psql on the disposable database
        psql = pg_exec.split()
    else:
        if not shutil.which('psql') or not Path(pg_ca).exists():
            record('consumer receipts via psql', False, f'psql or {pg_ca} missing')
        u = urlparse(env['JDBC_URL'][len('jdbc:'):])
        psql = ['psql', f"host={u.hostname} port={u.port} dbname={u.path.lstrip('/')} user={env['JDBC_USERNAME']} sslmode=verify-full sslrootcert={pg_ca}"]
    q = (f"SELECT count(*)||'|'||count(*) FILTER (WHERE status='PUBLISHED')||'|'"
         f"||(SELECT count(*) FROM consumed_events WHERE aggregate_id='{payment}')||'|'"
         f"||(SELECT count(DISTINCT consumer_group) FROM consumed_events WHERE aggregate_id='{payment}')||'|'"
         f"||(SELECT count(*) FROM shadow_comparisons WHERE payment_id='{payment}') FROM outbox_events WHERE aggregate_id='{payment}'")
    deadline = time.monotonic() + 120
    counts = ''
    while time.monotonic() < deadline:
        out = subprocess.run(psql + ['-tAc', q], capture_output=True, text=True, env={**os.environ, 'PGPASSWORD': env.get('JDBC_PASSWORD', '')})
        counts = out.stdout.strip() or out.stderr.strip()[:120]
        if counts == '3|3|6|2|1':
            break
        time.sleep(3)
    record('3 events published, 6 receipts across both groups, 1 shadow comparison', counts == '3|3|6|2|1', counts)
    out = subprocess.run(psql + ['-tAc', "SELECT count(*) FROM (SELECT event_id, consumer_group FROM consumed_events GROUP BY 1,2 HAVING count(*)>1) d"],
                         capture_output=True, text=True, env={**os.environ, 'PGPASSWORD': env.get('JDBC_PASSWORD', '')})
    record('no duplicate receipts anywhere', out.stdout.strip() == '0', out.stdout.strip())


def phase_limiter(c, env):
    print('NOTE: this locks authentication attempts from THIS address for the 15-minute window.', flush=True)
    statuses = []
    for i in range(11):
        spoof = {'X-Forwarded-For': f'203.0.113.{i + 1}', 'Forwarded': f'for=203.0.113.{i + 1};proto=https',
                 'X-Real-IP': f'198.51.100.{i + 1}', 'CF-Connecting-IP': f'192.0.2.{i + 1}'}
        st, _, h, _, _ = c.req('GET', f'/v1/accounts/{ACCOUNT}', user='visitor:not-the-password', headers=spoof)
        statuses.append(st)
    record('ten failures with rotating forwarding headers are all 401', statuses[:10] == [401] * 10, str(statuses[:10]))
    record('eleventh attempt is 429 despite fresh spoofed headers', statuses[10] == 429, f'status={statuses[10]} retry-after={h.get("retry-after")}')
    st, _, _, _, _ = c.req('GET', f'/v1/accounts/{ACCOUNT}', user='visitor:' + env['VISITOR_PASSWORD'], headers={'CF-Connecting-IP': '192.0.2.200', 'X-Forwarded-For': '203.0.113.200'})
    record('correct credentials from the locked address are still 429 (headers did not mint a new client)', st == 429, f'status={st}')
    st, _, _, _, _ = c.req('GET', '/actuator/health/readiness')
    record('non-attempt requests pass through the lockout', st == 200, f'status={st}')
    print('MANUAL: from a genuinely different network (e.g. phone hotspot) the visitor must sign in normally now.', flush=True)


def phase_cold(c):
    t0 = time.monotonic()
    first = None
    deadline = t0 + 600
    while time.monotonic() < deadline:
        try:
            st, _, _, _, el = c.req('GET', '/actuator/health/readiness', timeout=90)
            if first is None:
                first = (st, el)
            if st == 200:
                break
        except Exception as e:
            if first is None:
                first = ('error', str(e)[:60])
        time.sleep(3)
    total = round(time.monotonic() - t0, 1)
    record('readiness after idle', st == 200, f'first answer={first} ready after {total}s')


def main():
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument('--base', required=True)
    a.add_argument('--phase', default='all', choices=['all', 'readonly', 'session', 'flow', 'limiter', 'cold'])
    a.add_argument('--env', required=True, help="the deployment's environment sheet, kept outside this repository")
    a.add_argument('--pg-ca', help="the provider's PostgreSQL CA (default: postgres-ca.pem beside --env)")
    a.add_argument('--expect-commit', default='ca61492a80c6e3da835376c88a5325425ad2012d')
    a.add_argument('--insecure', action='store_true', help='rehearsal only: accept a tls-internal certificate')
    a.add_argument('--pg-exec', help='rehearsal only: psql command prefix for the disposable database')
    args = a.parse_args()
    env_path = Path(args.env).expanduser().resolve()
    if not env_path.is_file():
        raise SystemExit(f'no environment sheet at {env_path}')
    out_dir = env_path.parent
    pg_ca = args.pg_ca or str(out_dir / 'postgres-ca.pem')
    env = load_env(env_path)
    c = Client(args.base.rstrip('/'), insecure=args.insecure)
    started = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    try:
        if args.phase in ('all', 'readonly'):
            phase_readonly(c, args.expect_commit)
        if args.phase in ('all', 'session'):
            phase_session(c, env)
        if args.phase in ('all', 'flow'):
            phase_flow(c, env, pg_ca, args.pg_exec)
        if args.phase in ('all', 'limiter'):
            phase_limiter(c, env)
        if args.phase == 'cold':
            phase_cold(c)
    finally:
        out = out_dir / f'live-check-{started}-{args.phase}.json'
        out.write_text(json.dumps({'base': args.base, 'phase': args.phase, 'started': started,
                                   'passed': sum(r['ok'] for r in results), 'failed': sum(not r['ok'] for r in results),
                                   'results': results}, indent=2))
        print(f'{sum(r["ok"] for r in results)} passed, {sum(not r["ok"] for r in results)} failed -> {out}', flush=True)


if __name__ == '__main__':
    main()
