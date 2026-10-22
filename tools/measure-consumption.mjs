// G4: consumo real del APK release en el emulador.
//  - CPU en reposo: tiempo de CPU del proceso durante 120 s sin consultas provocadas.
//  - CPU por consulta: rafaga de 300 resoluciones unicas (mitad bloqueadas, mitad reenviadas por DoH).
//  - Memoria: PSS total (dumpsys meminfo).  - Wakelocks: ninguno retenido.
//  - Bateria: estimacion de batterystats (informativa: en emulador es un modelo, no un amperimetro).
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, PKG, adb, adbTry, gradle, sleep, fail } from './env.mjs';

const IDLE_S = 120;
const BURST = 300;
const budget = { idleCpuS: 1.0, cpuMsPerQuery: 3.0, pssMb: 60 };

const b = gradle([':app:assembleRelease']);
if (b.code !== 0) fail('assembleRelease fallo\n' + b.out.slice(-3000));
const apk = path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk');
const inst = adbTry('install', '-r', apk);
if (!/Success/.test(inst.out)) fail('install: ' + inst.out);
adb('shell', 'appops', 'set', PKG, 'ACTIVATE_VPN', 'allow');
adbTry('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS');

// Arranque como un usuario: abrir la app y tocar el instrumento (el release no tiene receptor de pruebas).
adbTry('shell', 'am', 'force-stop', PKG);
adb('shell', 'am', 'start', '-n', `${PKG}/.ui.MainActivity`);
await sleep(2500);
const ui = adb('shell', 'uiautomator dump /sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml');
// El interruptor grande de Inicio: su circulo lleva la descripcion "Protección apagada" cuando esta apagado.
const off = /content-desc="Protección apagada"/.test(ui);
if (off) {
  const m = ui.match(/content-desc="Protección apagada"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if (!m) fail('no encuentro el interruptor en pantalla');
  adb('shell', 'input', 'tap', String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1));
}
let up = false;
for (let i = 0; i < 30 && !up; i++) { await sleep(500); up = /10\.111\.222\.1/.test(adbTry('shell', 'ip addr show tun0').out); }
if (!up) fail('la VPN no se levanto desde la UI');
await sleep(3000); // carga de listas
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME'); // la UI deja de refrescar: medimos solo el servicio
await sleep(3000);

const pid = adb('shell', 'pidof', PKG).split(/\s+/)[0];
if (!pid) fail('sin proceso');
const cpuTicks = () => {
  const f = adb('shell', `cat /proc/${pid}/stat`).split(') ')[1].split(' ');
  return Number(f[11]) + Number(f[12]); // utime + stime (campos 14 y 15), en ticks de 10 ms
};
const hz = 100;

const tag = Date.now().toString(36);
const burst = (prefix, domain, n) =>
  `i=0; while [ $i -lt ${n} ]; do ping -c1 -W1 ${prefix}$i-${tag}.${domain} >/dev/null 2>&1; i=$((i+1)); done`;
function measureBurst(prefix, domain, n) {
  const c0 = cpuTicks();
  const w0 = Date.now();
  adb('shell', burst(prefix, domain, n));
  return { wallS: (Date.now() - w0) / 1000, cpuMs: ((cpuTicks() - c0) / hz) * 1000 };
}

// Calentamiento (no se mide): la primera vez Android compila el codigo (JIT) y DoH abre su conexion TLS.
// En el movil real ese coste se paga una vez; lo que importa para la bateria es el regimen estable.
const cold = measureBurst('w', 'example.org', 60);
await sleep(20000); // deja terminar la carga/actualizacion de listas del primer arranque

adbTry('shell', 'dumpsys', 'batterystats', '--reset');

// --- reposo
const t0 = cpuTicks();
await sleep(IDLE_S * 1000);
const idleCpuS = (cpuTicks() - t0) / hz;
const threadsIdle = Number(adb('shell', `ls /proc/${pid}/task | wc -l`));

// --- rafagas en caliente: bloqueadas (se contestan al momento) y reenviadas (DoH al servidor)
const blk = measureBurst('b', 'doubleclick.net', BURST / 2);
const fwd = measureBurst('f', 'example.com', BURST / 2);
const burstWallS = blk.wallS + fwd.wallS;
const burstCpuMs = blk.cpuMs + fwd.cpuMs;
const cpuMsPerQuery = burstCpuMs / BURST;

// --- memoria y wakelocks
const mem = adb('shell', 'dumpsys', 'meminfo', PKG);
const pssKb = Number((mem.match(/TOTAL PSS:\s+(\d+)/) || mem.match(/TOTAL\s+(\d+)/) || [0, 0])[1]);
const power = adb('shell', 'dumpsys', 'power');
const uid = (adb('shell', 'dumpsys', 'package', PKG).match(/userId=(\d+)/) || [])[1];
const wakelocks = power.split('\n').filter((l) => /PARTIAL_WAKE_LOCK|FULL_WAKE_LOCK/.test(l) && (l.includes(PKG) || (uid && l.includes(`uid=${uid}`))));
const bs = adbTry('shell', 'dumpsys', 'batterystats', PKG).out;
const mah = (bs.match(/UID u0a\d+: ([\d.]+)/) || bs.match(/Uid u0a\d+: ([\d.]+)/) || [])[1] || 'n/d';

const report = {
  build: 'release', idleSeconds: IDLE_S, idleCpuS, threadsIdle,
  burstQueries: BURST, burstWallS, burstCpuMs, cpuMsPerQuery: +cpuMsPerQuery.toFixed(3),
  blockedMsPerQuery: +(blk.cpuMs / (BURST / 2)).toFixed(3), forwardedMsPerQuery: +(fwd.cpuMs / (BURST / 2)).toFixed(3),
  coldMsPerQuery: +(cold.cpuMs / 60).toFixed(3),
  pssMb: +(pssKb / 1024).toFixed(1), wakelocks: wakelocks.length, batteryEstimateMah: mah, budget,
};
fs.writeFileSync(path.join(ROOT, 'tools/consumption-report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report));

const errs = [];
if (idleCpuS >= budget.idleCpuS) errs.push(`CPU en reposo ${idleCpuS}s >= ${budget.idleCpuS}s`);
if (cpuMsPerQuery >= budget.cpuMsPerQuery) errs.push(`CPU por consulta ${cpuMsPerQuery.toFixed(2)}ms >= ${budget.cpuMsPerQuery}ms`);
if (!(pssKb > 0)) errs.push('no pude leer el PSS');
if (pssKb / 1024 >= budget.pssMb) errs.push(`PSS ${(pssKb / 1024).toFixed(1)}MB >= ${budget.pssMb}MB`);
if (wakelocks.length) errs.push('wakelocks retenidos:\n' + wakelocks.join('\n'));
if (errs.length) fail(errs.join('\n'));
console.log('consumption within budget');
