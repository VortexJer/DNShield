// Entorno comun de las herramientas: JDK, SDK y adb, sin depender del PATH del usuario.
import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const JAVA_HOME = 'C:/Android/jdk-17.0.13+11';
export const SDK = 'C:/Android/sdk';
export const ADB = `${SDK}/platform-tools/adb.exe`;
export const PKG = 'dev.dnshield.app';
export const env = { ...process.env, JAVA_HOME, ANDROID_HOME: SDK };

export function gradle(args, opts = {}) {
  const r = spawnSync('.\\gradlew.bat', [...args, '--console=plain', '-q'], {
    cwd: ROOT, env, encoding: 'utf8', shell: true, maxBuffer: 64 << 20, ...opts,
  });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') };
}

export function adb(...args) {
  return execFileSync(ADB, args, { encoding: 'utf8', env, maxBuffer: 64 << 20 }).trim();
}

export function adbTry(...args) {
  const r = spawnSync(ADB, args, { encoding: 'utf8', env, maxBuffer: 64 << 20 });
  return { code: r.status, out: ((r.stdout || '') + (r.stderr || '')).trim() };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function fail(msg) {
  console.log('FAIL: ' + msg);
  process.exit(1);
}
