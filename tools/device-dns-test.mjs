// G3: prueba real en el emulador. Instala la build debug, levanta la VPN y resuelve dominios con el
// resolutor de Android (ping desde adb shell), comparando contra un control sin la app activa.
import path from 'node:path';
import { ROOT, PKG, adb, adbTry, gradle, sleep, fail } from './env.mjs';

const rnd = Date.now().toString(36);

function resolve(host) {
  const r = adbTry('shell', `ping -c 1 -W 2 ${host} 2>&1 | head -1`);
  const m = r.out.match(/PING [^ ]+ \(([^)]+)\)/);
  if (m) return m[1];
  if (/unknown host/i.test(r.out)) return 'UNKNOWN';
  return 'ERR:' + r.out.slice(0, 80);
}

async function cmd(c, arg = '', arg2 = '') {
  adb('logcat', '-c');
  const extra = [];
  if (arg) extra.push('--es', 'arg', arg);
  if (arg2) extra.push('--es', 'arg2', arg2);
  adb('shell', 'am', 'broadcast', '-a', 'dev.dnshield.app.debug.CMD', '-n', `${PKG}/.debug.DebugReceiver`, '--es', 'cmd', c, ...extra);
  for (let i = 0; i < 20; i++) {
    const log = adbTry('logcat', '-d', '-s', 'DNShieldTest:I').out;
    const m = log.match(/RESULT [^\n]*/);
    if (m) return m[0];
    await sleep(250);
  }
  fail('el receptor de pruebas no contesto a ' + c);
}

async function waitRunning(want) {
  for (let i = 0; i < 40; i++) {
    const st = await cmd('status');
    if (st.includes(`running=${want}`) && (!want || !st.includes('rules=0'))) return st;
    await sleep(500);
  }
  fail('la VPN no llego a running=' + want);
}

// ping muestra 0.0.0.0 como 127.0.0.1 (el kernel lo trata como local); el control sin la app da IP real.
const isBlock = (ip) => ip === '0.0.0.0' || ip === '::' || ip === '127.0.0.1';
const isReal = (ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip) && !isBlock(ip);

// --- instalar
const b = gradle([':app:assembleDebug']);
if (b.code !== 0) fail('assembleDebug fallo\n' + b.out.slice(-3000));
const apk = path.join(ROOT, 'app/build/outputs/apk/debug/app-debug.apk');
const inst = adbTry('install', '-r', '-t', apk);
if (!/Success/.test(inst.out)) fail('install: ' + inst.out);
adb('shell', 'appops', 'set', PKG, 'ACTIVATE_VPN', 'allow');
adbTry('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS');

// --- control: con la app parada, el dominio de anuncios resuelve a una IP real
await cmd('stop');
await sleep(1500);
const ctl = resolve('stats.g.doubleclick.net');
console.log('control sin DNShield: stats.g.doubleclick.net ->', ctl);
if (!isReal(ctl)) fail('el control debe resolver a IP real (red del emulador?): ' + ctl);

// --- arrancar
await cmd('clear');
await cmd('server', 'cloudflare');
await cmd('proto', 'cloudflare', 'plain');
console.log(await cmd('start'));
console.log(await waitRunning(true));
const tun = adbTry('shell', 'ip addr show tun0').out;
if (!/10\.111\.222\.1/.test(tun)) fail('no hay tun0 con 10.111.222.1:\n' + tun);
console.log('tun0 arriba con 10.111.222.1');

const checks = [];
const expect = (label, host, pred, why) => {
  const ip = resolve(host);
  const ok = pred(ip);
  console.log(`${ok ? 'OK ' : 'MAL'} ${label}: ${host} -> ${ip}`);
  checks.push(ok || `${label}: ${host} -> ${ip} (${why})`);
};

// listas: anuncios bloqueados, sitios normales resuelven
expect('anuncio (lista)', 'stats.g.doubleclick.net', isBlock, 'debia bloquearse');
expect('anuncio subdominio nuevo', `x${rnd}.doubleclick.net`, isBlock, 'debia bloquearse por sufijo');
expect('rastreador', 'app-measurement.com', isBlock, 'debia bloquearse');
expect('normal', 'example.com', isReal, 'debia resolver');
expect('normal 2', 'wikipedia.org', isReal, 'debia resolver');

// reglas del usuario en vivo (dominios nunca consultados antes, para esquivar la cache de Android)
await cmd('block', 'iana.org');
expect('bloqueo propio', 'www.iana.org', isBlock, 'la regla propia debia bloquear');
await cmd('allow', 'googleads.g.doubleclick.net');
expect('permitido propio', 'googleads.g.doubleclick.net', isReal, 'el permitido debia ganar a la lista');

// DoH: cambiar el servidor en caliente y seguir resolviendo
await cmd('proto', 'cloudflare', 'doh');
expect('DoH normal', 'mozilla.org', isReal, 'DoH debia resolver');
expect('DoH anuncio', `y${rnd}.doubleclick.net`, isBlock, 'debia bloquearse con DoH');
await cmd('proto', 'cloudflare', 'dot');
expect('DoT normal', 'debian.org', isReal, 'DoT debia resolver');

await cmd('clear');
const bad = checks.filter((c) => c !== true);
if (bad.length) fail(bad.join('\n'));
console.log('device dns test passed');
