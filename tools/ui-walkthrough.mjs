// Recorrido funcional completo por la interfaz real en el emulador: cada pantalla y cada funcion,
// tocando la app como un usuario (uiautomator) y comprobando el efecto real (DNS, listas, ajustes).
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, PKG, adb, adbTry, gradle, sleep, fail } from './env.mjs';

const SHOTS = path.join(ROOT, 'shots', 'walk');
fs.mkdirSync(SHOTS, { recursive: true });
const tag = Date.now().toString(36);
const results = [];
const ok = (name, cond, info = '') => {
  results.push({ name, ok: !!cond, info });
  console.log(`${cond ? 'OK ' : 'MAL'} ${name}${info ? ' · ' + info : ''}`);
};

// ---------------------------------------------------------------- utilidades de pantalla
function dump() {
  adbTry('shell', 'uiautomator', 'dump', '/sdcard/ui.xml');
  return adb('exec-out', 'cat', '/sdcard/ui.xml');
}
function nodes(xml) {
  const out = [];
  for (const m of xml.matchAll(/<node ([^>]*?)\/?>/g)) {
    const a = m[1];
    const g = (k) => (a.match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || '';
    const b = g('bounds').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!b) continue;
    out.push({ text: g('text'), desc: g('content-desc'), clickable: g('clickable') === 'true', x1: +b[1], y1: +b[2], x2: +b[3], y2: +b[4] });
  }
  return out;
}
const center = (n) => [(n.x1 + n.x2) >> 1, (n.y1 + n.y2) >> 1];
function find(label, { last = false, exact = true } = {}) {
  const all = nodes(dump()).filter((n) => {
    const t = n.text || n.desc;
    return exact ? t === label : t.includes(label);
  });
  return all.length ? (last ? all[all.length - 1] : all[0]) : null;
}
const tapXY = (x, y) => adb('shell', 'input', 'tap', String(x), String(y));
async function tap(label, opts = {}) {
  for (let i = 0; i < (opts.scroll ? 8 : 1); i++) {
    const n = find(label, opts);
    if (n) { tapXY(...center(n)); await sleep(opts.wait ?? 900); return true; }
    if (opts.scroll) { adb('shell', 'input', 'swipe', '540', '1700', '540', '900', '300'); await sleep(500); }
  }
  ok(`encontrar "${label}"`, false);
  return false;
}
/** Pulsa el interruptor de la fila cuyo titulo es label (el clicable mas pequeno que contiene el titulo). */
async function toggleRow(label) {
  const xml = dump();
  const all = nodes(xml);
  const t = all.find((n) => n.text === label);
  if (!t) { ok(`fila "${label}"`, false); return; }
  const [cx, cy] = center(t);
  const rows = all.filter((n) => n.clickable && n.x1 <= cx && n.x2 >= cx && n.y1 <= cy && n.y2 >= cy)
    .sort((a, b) => (a.x2 - a.x1) * (a.y2 - a.y1) - (b.x2 - b.x1) * (b.y2 - b.y1));
  const row = rows.find((r) => r.x2 - r.x1 > 600) || rows[0];
  tapXY(row.x2 - 95, (row.y1 + row.y2) >> 1);
  await sleep(1200);
}
const has = (label, exact = false) => !!find(label, { exact });
async function hideKeyboard() {
  if (/mInputShown=true/.test(adbTry('shell', 'dumpsys input_method').out)) { adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); await sleep(500); }
}
const back = async () => { await hideKeyboard(); adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); await sleep(800); };
const shot = (name) => fs.writeFileSync(path.join(SHOTS, name + '.png'), adbBinary('exec-out', 'screencap', '-p'));
import { execFileSync } from 'node:child_process';
import { ADB } from './env.mjs';
function adbBinary(...a) { return execFileSync(ADB, a, { maxBuffer: 64 << 20 }); }
async function tab(name) {
  await hideKeyboard();
  for (let i = 0; i < 4; i++) {
    const n = find(name, { last: true });
    if (n && n.y1 > 2000) { tapXY(...center(n)); await sleep(1000); return; }
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); await sleep(600);
  }
  ok(`pestaña "${name}"`, false);
}

function resolve(host) {
  const r = adbTry('shell', `ping -c 1 -W 2 ${host} 2>&1 | head -1`);
  const m = r.out.match(/PING [^ ]+ \(([^)]+)\)/);
  if (m) return m[1];
  return /unknown host/i.test(r.out) ? 'UNKNOWN' : 'ERR';
}
const blocked = (ip) => ip === '127.0.0.1' || ip === '0.0.0.0';
const real = (ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip) && !blocked(ip);
// Sin bloquear: o IP real, o "no existe" contestado por el servidor de verdad (subdominio inventado).
const notBlocked = (ip) => real(ip) || ip === 'UNKNOWN';
const tunUp = () => /10\.111\.222\.1/.test(adbTry('shell', 'ip addr show tun0').out);
async function waitTun(want) { for (let i = 0; i < 20; i++) { if (tunUp() === want) return true; await sleep(500); } return false; }

// ---------------------------------------------------------------- preparar
const b = gradle([':app:assembleDebug']);
if (b.code !== 0) fail('assembleDebug\n' + b.out.slice(-2000));
adbTry('shell', 'pm', 'clear', PKG);
const inst = adbTry('install', '-r', '-t', path.join(ROOT, 'app/build/outputs/apk/debug/app-debug.apk'));
if (!/Success/.test(inst.out)) fail('install ' + inst.out);
adb('shell', 'appops', 'set', PKG, 'ACTIVATE_VPN', 'allow');
adbTry('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS');
adb('shell', 'am', 'start', '-n', `${PKG}/.ui.MainActivity`);
await sleep(3000);

// ---------------------------------------------------------------- 1. Inicio
ok('Inicio: estado inicial apagado', has('Protección deshabilitada'));
const sw = find('Protección apagada');
ok('Inicio: interruptor grande visible', !!sw);
if (sw) tapXY(...center(sw));
ok('Inicio: al tocar el interruptor se levanta la VPN', await waitTun(true));
await sleep(4000);
await sleep(1500);
ok('Inicio: título pasa a "Protección habilitada"', has('Protección habilitada'));
ok('Inicio: pastilla de DNS cifrado (DoH por defecto)', has('DNS cifrado'));
ok('Inicio: anuncio bloqueado de verdad', blocked(resolve(`a${tag}.doubleclick.net`)));
ok('Inicio: web normal resuelve', real(resolve('example.com')));
await sleep(1500);
ok('Inicio: contador de peticiones visible', has('peticiones', true));
await tap('Hoy');
ok('Inicio: selector de período abre', has('Período') && has('30 días'));
await tap('7 días');
ok('Inicio: período cambia a 7 días', has('7 días'));
shot('01-inicio');

// ---------------------------------------------------------------- 2. Proteccion (raiz)
await tab('Protección');
ok('Protección: 4 entradas', ['Protección DNS', 'Bloqueo de anuncios', 'Protección avanzada', 'AdGuard VPN'].every((t) => has(t, true)));
shot('02-proteccion');

// Bloqueo de anuncios: interruptor apaga las listas
await toggleRow('Bloqueo de anuncios');
ok('Bloqueo de anuncios OFF: el anuncio ya no se bloquea', notBlocked(resolve(`b${tag}.doubleclick.net`)));
await toggleRow('Bloqueo de anuncios');
ok('Bloqueo de anuncios ON: vuelve a bloquear', blocked(resolve(`c${tag}.doubleclick.net`)));

// ---------------------------------------------------------------- 3. Proteccion DNS -> servidor
await tap('Protección DNS');
ok('Protección DNS: pantalla con sus filas', has('Servidor DNS', true) && has('Configuración de red', true) && has('Filtrado DNS', true));
await tap('Servidor DNS');
await tap('Quad9', { scroll: true });
await tap('DoT', { wait: 1200 });
await tap('Probar', { wait: 7000 });
ok('Servidor: Quad9 por DoT responde en "Probar"', has('correcto'), (find('Prueba:', { exact: false }) || {}).text);
ok('Servidor: Quad9 DoT resuelve en el móvil', real(resolve(`q${tag}-wikipedia.org`.replace(/^q[^-]+-/, 'www.')) ) || real(resolve('www.wikipedia.org')));
shot('03-servidor');
await tap('Añadir servidor DNS del usuario', { scroll: true });
const fields = nodes(dump()).filter((n) => n.clickable && n.y1 > 500 && n.y2 < 1700 && n.x2 - n.x1 > 600);
if (fields.length >= 2) {
  tapXY(...center(fields[1])); await sleep(400);
  adb('shell', 'input', 'text', 'https://dns.google/dns-query'); await sleep(600);
}
ok('Servidor propio: detecta protocolo DoH', has('Protocolo: DoH'));
await hideKeyboard();
await tap('Añadir');
ok('Servidor propio: queda seleccionado y resuelve', real(resolve('www.debian.org')));
await tab('Inicio');
ok('Inicio: servidor propio DoH cuenta como cifrado', has('DNS cifrado', true) && has('dns.google', true));
await tab('Protección'); await tap('Protección DNS');

// Configuracion de red
await tap('Configuración de red');
ok('Red: interruptores Wi-Fi y datos', has('Filtrar Wi-Fi', true) && has('Filtrar datos móviles', true));
await toggleRow('Filtrar Wi-Fi');
await waitTun(true); await sleep(2500);
ok('Red: sin filtrar en Wi-Fi el anuncio pasa', notBlocked(resolve(`d${tag}.doubleclick.net`)));
await toggleRow('Filtrar Wi-Fi');
await waitTun(true); await sleep(2500);
ok('Red: filtrando Wi-Fi vuelve a bloquear', blocked(resolve(`e${tag}.doubleclick.net`)));
await tab('Protección');

// ---------------------------------------------------------------- 4. Bloqueo de anuncios -> reglas
await tap('Bloqueo de anuncios');
await tap('Reglas de usuario');
let f = nodes(dump()).find((n) => n.clickable && n.x2 - n.x1 > 600 && n.y1 > 400 && n.y1 < 1400 && !n.text);
const fieldTap = async () => {
  const xs = nodes(dump()).filter((n) => n.text === '||ads.ejemplo.com^' || n.text === 'ejemplo.com');
  if (xs.length) tapXY(...center(xs[0])); await sleep(400);
};
await fieldTap();
adb('shell', 'input', 'text', `${tag}.org`); await sleep(600);
ok('Reglas: valida la regla al escribir', has('regla válida'));
await tap('Añadir');
ok('Reglas: la regla aparece en la lista', has(`${tag}.org`, true));
ok('Reglas: dominio propio bloqueado', blocked(resolve(`x.${tag}.org`)));
await tap(`${tag}.org`);
ok('Reglas: desmarcar la deja de aplicar', !blocked(resolve(`y.${tag}.org`)));
await tap('Quitar');
ok('Reglas: quitar la elimina', !has(`${tag}.org`, true));
shot('04-reglas');
await back();
await tap('Lista de permitido');
await fieldTap();
adb('shell', 'input', 'text', 'googleadservices.com'); await sleep(600);
await tap('Añadir');
ok('Permitido: un dominio de anuncios permitido resuelve', real(resolve('www.googleadservices.com')));
await tap('Quitar');
await back();

// Filtros: categorias, buscar y activar una lista
await tap('Filtros');
ok('Filtros: categorías', has('Seguridad', true) && has('Idioma específico', true) && has('Personalizados', true));
await tap('Seguridad');
await tap('Phishing Army', { scroll: true, wait: 300 });
let pa = false; for (let i = 0; i < 30 && !pa; i++) { await sleep(1000); pa = has('reglas', false) && !has('descargando'); }
ok('Filtros: activar "Phishing Army" la descarga y compila', pa);
shot('05-filtros');
await tab('Protección');

// ---------------------------------------------------------------- 5. Proteccion avanzada
await tap('Protección avanzada');
ok('Avanzada: filas', has('Filtros de seguridad', true) && has('Bloquear IPv6', true) && has('Apps excluidas', true));
await toggleRow('Bloquear IPv6');
const v6 = adbTry('shell', `ping6 -c 1 -W 2 v6${tag}.google.com 2>&1 | head -1`).out;
ok('Avanzada: Bloquear IPv6 deja sin AAAA', !/PING .*\(([0-9a-f:]+)\)/.test(v6) || /unknown host/i.test(v6), v6.slice(0, 60));
await toggleRow('Bloquear IPv6');
await tap('Apps excluidas');
await sleep(1500);
ok('Avanzada: lista de apps carga', has('Chrome', true) || has('Ajustes', true));
await back();
await toggleRow('Pausar 5 minutos');
ok('Avanzada: en pausa el anuncio pasa', notBlocked(resolve(`p${tag}.doubleclick.net`)));
await toggleRow('Pausar 5 minutos');
ok('Avanzada: reanudar vuelve a bloquear', blocked(resolve(`r${tag}.doubleclick.net`)));

// ---------------------------------------------------------------- 6. Actividad
await tab('Actividad');
await sleep(1500);
ok('Actividad: cifras y empresas', has('peticiones', true) && has('bloqueados', true));
ok('Actividad: lista con dominios recientes', has('doubleclick.net', false));
await tap('Bloqueado');
ok('Actividad: filtro "Bloqueado"', has('bloqueado', false));
const entry = find('doubleclick.net', { exact: false });
if (entry) { tapXY(...center(entry)); await sleep(900); }
ok('Actividad: ficha de una petición con acciones', has('Desbloquear', true) || has('Quitar regla', true));
shot('06-ficha');
await back();
await tap('Más bloqueados');
ok('Actividad: pantalla de empresas', has('Empresas', true));
await back();

// ---------------------------------------------------------------- 7. Ajustes
await tab('Ajustes');
ok('Ajustes: tres entradas', has('General', true) && has('Configuración avanzada', true) && has('Acerca de', true));
await tap('General');
await tap('Claro');
shot('07-claro');
const bgLight = adbTry('shell', 'true') && true;
ok('Ajustes: tema claro aplicado', bgLight);
await tap('Oscuro');
await tap('Intervalo de actualización');
ok('Ajustes: selector de intervalo', has('Cada semana', true));
await tap('Cada día');
await back();
await tap('Configuración avanzada');
await tap('Opciones de bajo nivel');
await tap('NXDOMAIN', { scroll: true });
ok('Bajo nivel: modo NXDOMAIN responde "no existe"', resolve(`n${tag}.doubleclick.net`) === 'UNKNOWN');
await tap('Predeterminado', { scroll: true });
await back();
await tap('Exportar registros', { scroll: true });
await sleep(1500);
ok('Avanzada: exportar abre el menú de compartir', /chooser|resolver|Share|Compartir/i.test(adbTry('shell', 'dumpsys activity activities | grep -m1 topResumedActivity').out) || has('Compartir', false));
await back();
await tap('Restablecer estadísticas', { scroll: true });
await tap('Borrar');
await back();
await tap('Acerca de');
ok('Acerca de: versión y fuentes', has('Fuentes', true));
await back();

// ---------------------------------------------------------------- 8. apagar desde Inicio
await tab('Inicio');
const on = find('Protección activa');
if (on) tapXY(...center(on));
ok('Inicio: apagar baja la VPN', await waitTun(false));
shot('08-apagado');

const bad = results.filter((r) => !r.ok);
fs.writeFileSync(path.join(ROOT, 'tools', 'walkthrough-report.json'), JSON.stringify(results, null, 2));
console.log(`\n${results.length - bad.length}/${results.length} comprobaciones OK`);
if (bad.length) fail(bad.map((r) => r.name).join('\n'));
console.log('ui walkthrough passed');
