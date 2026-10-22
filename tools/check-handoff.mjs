// G7: el APK del Escritorio es exactamente el ultimo release construido y la guia cubre lo esencial.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { ROOT, fail } from './env.mjs';

const desk = path.join(os.homedir(), 'Desktop');
const built = path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk');
const apk = path.join(desk, 'DNShield.apk');
const guide = path.join(desk, 'DNShield - guia.md');
for (const f of [built, apk, guide]) if (!fs.existsSync(f)) fail('falta ' + f);
const h = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
if (h(apk) !== h(built)) fail('el APK del Escritorio no es el ultimo release construido');
// el release no debe ser mas viejo que el codigo fuente
const newest = (dir) => fs.readdirSync(dir, { recursive: true }).map((f) => path.join(dir, f))
  .filter((f) => fs.statSync(f).isFile()).reduce((m, f) => Math.max(m, fs.statSync(f).mtimeMs), 0);
if (fs.statSync(built).mtimeMs < newest(path.join(ROOT, 'app/src/main'))) fail('el release es anterior al ultimo cambio de codigo');
const text = fs.readFileSync(guide, 'utf8');
for (const need of ['Instalar en el móvil', 'VPN siempre activa', 'DNS privado', 'YouTube']) {
  if (!text.includes(need)) fail('a la guia le falta: ' + need);
}
console.log(`apk sha256=${h(apk).slice(0, 16)} guia=${text.length} chars`);
console.log('handoff ready');
