// Prueba de seguridad del APK release: superficie expuesta (estatica) y ataques desde el propio movil (dinamica).
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { ROOT, SDK, PKG, env, adb, adbTry, gradle, sleep, fail } from './env.mjs';

const results = [];
const check = (name, cond, info = '') => {
  results.push({ name, ok: !!cond, info });
  console.log(`${cond ? 'OK ' : 'MAL'} ${name}${info ? ' · ' + info : ''}`);
};

// ---------------------------------------------------------------- estatico: el APK release
const b = gradle([':app:assembleRelease']);
if (b.code !== 0) fail('assembleRelease\n' + b.out.slice(-2000));
const apk = path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk');
const bt = fs.readdirSync(path.join(SDK, 'build-tools')).sort().pop();
const aapt = path.join(SDK, 'build-tools', bt, 'aapt2.exe');
const mf = execFileSync(aapt, ['dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], { encoding: 'utf8', env, maxBuffer: 64 << 20 });
// aapt2 escribe los booleanos como =true/=false o como (type 0x12)0xffffffff/0x0 segun la version.
const attrTrue = (block, a) => new RegExp(a + '\\(0x[0-9a-f]+\\)=(true|\\(type 0x12\\)0xffffffff)').test(block);
const attrFalse = (block, a) => new RegExp(a + '\\(0x[0-9a-f]+\\)=(false|\\(type 0x12\\)0x0\\b)').test(block);
console.log('manifest exportados:', (mf.match(/exported\(0x[0-9a-f]+\)=\S+/g) || []).join(' '));

check('APK no depurable', !attrTrue(mf, 'debuggable'));
check('Sin receptor de pruebas en release', !/DebugReceiver/.test(mf));
check('Sin copia de seguridad (allowBackup=false)', attrFalse(mf, 'allowBackup'));
check('Config. de red propia (sin HTTP en claro)', /networkSecurityConfig/.test(mf) && !attrTrue(mf, 'usesCleartextTraffic'));

// componentes exportados: cada uno con su motivo
const comps = [...mf.matchAll(/E: (activity|service|receiver|provider)[\s\S]*?A: http:\/\/schemas.android.com\/apk\/res\/android:name\(0x01010003\)="([^"]+)"[\s\S]*?(?=\n\s+E: (?:activity|service|receiver|provider)|\n\s+E: (?:uses-|queries)|$)/g)]
  .map((m) => ({ kind: m[1], name: m[2], block: m[0] }));
const exported = comps.filter((c) => attrTrue(c.block, 'exported'));
check('El análisis ve los componentes del manifiesto', comps.length >= 4 && exported.length >= 1, `${comps.length} componentes, ${exported.length} exportados`);
const vpn = comps.find((c) => c.name.endsWith('ShieldVpnService'));
check('Servicio VPN no exportado y con BIND_VPN_SERVICE', vpn && !exported.includes(vpn) && /BIND_VPN_SERVICE/.test(vpn.block));
const tile = comps.find((c) => c.name.endsWith('ToggleTileService'));
check('Tile solo enlazable por el sistema (BIND_QUICK_SETTINGS_TILE)', tile && /BIND_QUICK_SETTINGS_TILE/.test(tile.block));
// Exportado y aceptable: los nuestros con motivo, o los protegidos por un permiso que solo tiene el sistema
// (androidx.profileinstaller exige android.permission.DUMP: sistema o depuracion USB).
const systemOnly = (c) => /permission\(0x01010006\)="android\.permission\.(DUMP|BIND_[A-Z_]+)"/.test(c.block);
const unexpected = exported.filter((c) => !/MainActivity|ToggleTileService|BootReceiver/.test(c.name) && !systemOnly(c));
check('Nada más exportado que actividad, tile y arranque', unexpected.length === 0, exported.map((c) => c.name.split('.').pop()).join(', '));

const dexHasDebug = execFileSync('C:/Windows/System32/tar.exe', ['-xOf', apk, 'classes.dex'], { maxBuffer: 256 << 20 }).includes(Buffer.from('DebugReceiver'));
check('El código del receptor de pruebas no viaja en el APK', !dexHasDebug);

// ---------------------------------------------------------------- dinamico: instalar release y encender desde la UI
adbTry('shell', 'pm', 'clear', PKG);
const inst = adbTry('install', '-r', apk);
if (!/Success/.test(inst.out)) fail('install ' + inst.out);
adb('shell', 'appops', 'set', PKG, 'ACTIVATE_VPN', 'allow');
adb('shell', 'am', 'start', '-n', `${PKG}/.ui.MainActivity`);
await sleep(3500);
adbTry('shell', 'uiautomator', 'dump', '/sdcard/ui.xml');
const ui = adb('exec-out', 'cat', '/sdcard/ui.xml');
const m = ui.match(/content-desc="Protección apagada"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
if (m) adb('shell', 'input', 'tap', String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1));
let up = false;
for (let i = 0; i < 30 && !up; i++) { await sleep(500); up = /10\.111\.222\.1/.test(adbTry('shell', 'ip addr show tun0').out); }
if (!up) fail('la VPN no subió');
await sleep(5000); // carga de listas
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
const pid0 = adb('shell', 'pidof', PKG).trim();
const blockedNow = (host) => /\((127\.0\.0\.1|0\.0\.0\.0)\)/.test(adbTry('shell', `ping -c1 -W2 ${host} 2>&1 | head -1`).out);
check('Línea base: el filtro bloquea (respuesta local, sin depender de internet)', blockedNow(`base${Date.now()}.doubleclick.net`));

// 1. otra app intentando manejar el servicio VPN
const s1 = adbTry('shell', 'am', 'start-foreground-service', '-n', `${PKG}/.vpn.ShieldVpnService`, '-a', 'dev.dnshield.app.STOP');
check('Otra app no puede parar/arrancar el servicio VPN', /not exported|Permission Denial|SecurityException|requires/i.test(s1.out) && /10\.111\.222\.1/.test(adbTry('shell', 'ip addr show tun0').out), s1.out.split('\n').pop());

// 2. señal de arranque falsificada
const s2 = adbTry('shell', 'am', 'broadcast', '-n', `${PKG}/.vpn.BootReceiver`, '-a', 'android.intent.action.BOOT_COMPLETED');
check('Arranque falsificado rechazado por el sistema', /Permission Denial|not allowed|SecurityException/i.test(s2.out), s2.out.split('\n').pop());

// 3. receptor de pruebas inexistente
const pkgDump = adb('shell', 'dumpsys', 'package', PKG);
check('No hay ningún receptor de control por comandos', !/DebugReceiver/.test(pkgDump));

// 4. datos privados
const s4a = adbTry('shell', 'ls', `/data/data/${PKG}`);
const s4b = adbTry('shell', 'run-as', PKG, 'ls');
check('Los datos de la app no se pueden leer desde fuera', /Permission denied|No such file/i.test(s4a.out) && /not debuggable|unknown package/i.test(s4b.out),
  `${s4a.out.split('\n')[0]} / ${s4b.out.split('\n')[0]}`);

// 5. la VPN solo enruta el DNS (no ve el resto del tráfico)
const conn = adb('shell', 'dumpsys', 'connectivity');
const tunLp = (conn.match(/InterfaceName: tun0[^}]*Routes: \[([^\]]*)\]/) || [])[1] || '';
check('La VPN solo enruta su DNS: ni webs, ni vídeo, ni apps pasan por ella', /10\.111\.222\.2\/32/.test(tunLp) && !/0\.0\.0\.0\/0 ->/.test(tunLp) && !/::\/0 ->/.test(tunLp), tunLp.trim());

// 6. basura al puerto DNS desde una app local
const hasNc = /nc/.test(adbTry('shell', 'which', 'nc').out);
if (hasNc) {
  adbTry('shell', `i=0; while [ $i -lt 400 ]; do head -c $((RANDOM % 900 + 1)) /dev/urandom | nc -u -w0 10.111.222.2 53; i=$((i+1)); done`);
}
check('Basura al puerto DNS: la app sigue viva', adb('shell', 'pidof', PKG).trim() === pid0, hasNc ? '400 paquetes aleatorios' : 'sin nc en el sistema');
check('Basura al puerto DNS: sigue filtrando', blockedNow(`after${Date.now()}.doubleclick.net`));

// 7. inundación de consultas desde 8 procesos a la vez
const t0 = Date.now();
const loops = Array.from({ length: 8 }, (_, k) => `(i=0; while [ $i -lt 150 ]; do ping -c1 -W1 f${k}x$i-${t0}.doubleclick.net >/dev/null 2>&1; i=$((i+1)); done) &`).join(' ');
adbTry('shell', `${loops} wait`);
const pssKb = Number((adb('shell', 'dumpsys', 'meminfo', PKG).match(/TOTAL PSS:\s+(\d+)/) || [0, 0])[1]);
check('Inundación (1.200 consultas en paralelo): la app sigue viva', adb('shell', 'pidof', PKG).trim() === pid0);
check('Inundación: memoria acotada (< 80 MB)', pssKb > 0 && pssKb < 80 * 1024, `${(pssKb / 1024).toFixed(1)} MB`);
check('Inundación: sigue filtrando después', blockedNow(`post${Date.now()}.doubleclick.net`));

const bad = results.filter((r) => !r.ok);
fs.writeFileSync(path.join(ROOT, 'tools', 'security-report.json'), JSON.stringify(results, null, 2));
console.log(`\n${results.length - bad.length}/${results.length} comprobaciones OK`);
if (bad.length) fail(bad.map((r) => r.name).join('\n'));
console.log('security test passed');
