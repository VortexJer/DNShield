// G1: corre los tests unitarios del nucleo y lee los informes JUnit (no se fia del texto de Gradle).
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, gradle, fail } from './env.mjs';

const dir = path.join(ROOT, 'app/build/test-results/testDebugUnitTest');
fs.rmSync(dir, { recursive: true, force: true });
const r = gradle([':app:testDebugUnitTest']);
if (!fs.existsSync(dir)) fail('no hay informes de test\n' + r.out.slice(-3000));
let tests = 0, failures = 0, errors = 0, skipped = 0;
for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.xml'))) {
  const head = fs.readFileSync(path.join(dir, f), 'utf8').match(/<testsuite[^>]*>/)[0];
  const n = (k) => Number((head.match(new RegExp(` ${k}="([0-9]+)"`)) || [0, 0])[1]);
  tests += n('tests'); failures += n('failures'); errors += n('errors'); skipped += n('skipped');
}
console.log(`tests=${tests} failures=${failures} errors=${errors} skipped=${skipped} gradle=${r.code}`);
if (r.code !== 0 || failures || errors) fail('hay tests rojos\n' + r.out.slice(-3000));
if (tests < 20) fail(`solo ${tests} tests (minimo 20)`);
console.log('unit tests passed');
