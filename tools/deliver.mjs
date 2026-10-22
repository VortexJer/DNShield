// Copia el APK release recien construido y la guia al Escritorio para pasarselos al movil.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { ROOT, gradle, fail } from './env.mjs';

const r = gradle([':app:assembleRelease']);
if (r.code !== 0) fail('assembleRelease fallo\n' + r.out.slice(-3000));
const desk = path.join(os.homedir(), 'Desktop');
fs.copyFileSync(path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk'), path.join(desk, 'DNShield.apk'));
fs.copyFileSync(path.join(ROOT, 'README.md'), path.join(desk, 'DNShield - guia.md'));
console.log('entregado en ' + desk);
