// G2: construye el APK release (R8 + recorte de recursos) y comprueba firma, tamano y contenido.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { ROOT, SDK, env, gradle, fail } from './env.mjs';

const r = gradle([':app:assembleRelease']);
if (r.code !== 0) fail('assembleRelease fallo\n' + r.out.slice(-4000));
const apk = path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk');
if (!fs.existsSync(apk)) fail('no existe ' + apk);
const mb = fs.statSync(apk).size / 1048576;
console.log(`apk=${apk} size=${mb.toFixed(2)}MB`);
if (mb >= 15) fail('el APK pesa 15 MB o mas');

const bt = fs.readdirSync(path.join(SDK, 'build-tools')).sort().pop();
const apksigner = path.join(SDK, 'build-tools', bt, 'apksigner.bat');
const sig = execFileSync('cmd', ['/c', apksigner, 'verify', '--print-certs', apk], { encoding: 'utf8', env });
if (!/Signer #1 certificate/.test(sig)) fail('APK sin firma valida\n' + sig);
const aapt = path.join(SDK, 'build-tools', bt, 'aapt2.exe');
const badging = execFileSync(aapt, ['dump', 'badging', apk], { encoding: 'utf8', env });
const pkg = (badging.match(/package: name='([^']+)'/) || [])[1];
const minSdk = (badging.match(/minSdkVersion:'(\d+)'/) || [])[1];
console.log(`package=${pkg} minSdk=${minSdk}`);
if (pkg !== 'dev.dnshield.app') fail('paquete inesperado: ' + pkg);
const list = execFileSync('C:/Windows/System32/tar.exe', ['-tf', apk], { encoding: 'utf8' });
for (const need of ['assets/lists/adguard.txt', 'assets/filters.json', 'assets/companies.tsv', 'classes.dex']) {
  if (!list.includes(need)) fail('falta en el APK: ' + need);
}
console.log('apk check passed');
