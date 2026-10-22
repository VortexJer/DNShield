// Diagnostico: CPU por consulta reenviada segun protocolo (build debug, con receptor de pruebas).
import path from 'node:path';
import { ROOT, PKG, adb, adbTry, gradle, sleep, fail } from './env.mjs';
const b = gradle([':app:assembleDebug']);
if (b.code !== 0) fail(b.out.slice(-2000));
adbTry('install', '-r', '-t', path.join(ROOT, 'app/build/outputs/apk/debug/app-debug.apk'));
adb('shell', 'appops', 'set', PKG, 'ACTIVATE_VPN', 'allow');
const send = (c, a = '', a2 = '') => adb('shell', 'am', 'broadcast', '-a', 'dev.dnshield.app.debug.CMD', '-n', `${PKG}/.debug.DebugReceiver`, '--es', 'cmd', c, ...(a ? ['--es', 'arg', a] : []), ...(a2 ? ['--es', 'arg2', a2] : []));
send('start'); await sleep(8000);
const pid = adb('shell', 'pidof', PKG).split(/\s+/)[0];
const ticks = () => { const f = adb('shell', `cat /proc/${pid}/stat`).split(') ')[1].split(' '); return +f[11] + +f[12]; };
const tag = Date.now().toString(36);
for (const [server, proto] of [['cloudflare', 'doh'], ['cloudflare', 'dot'], ['system', 'plain'], ['cloudflare', 'doh']]) {
  send('server', server); if (server !== 'system') send('proto', server, proto);
  await sleep(500);
  adb('shell', `i=0; while [ $i -lt 20 ]; do ping -c1 -W1 w$i-${proto}-${tag}.example.net >/dev/null 2>&1; i=$((i+1)); done`);
  const c0 = ticks(), w0 = Date.now();
  adb('shell', `i=0; while [ $i -lt 100 ]; do ping -c1 -W1 m$i-${server}${proto}-${tag}.example.com >/dev/null 2>&1; i=$((i+1)); done`);
  console.log(`${server}/${proto}: ${((ticks() - c0) * 10 / 100).toFixed(2)} ms CPU por consulta, ${((Date.now() - w0) / 100).toFixed(0)} ms reloj`);
  tag;
}
