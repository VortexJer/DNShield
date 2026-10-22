# Gates: DNShield para Android (bloqueador DNS tipo AdGuard, gratis)

OWNS: app/**, tools/**, README.md, GATES.md, build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/**, gradlew, gradlew.bat

Scope: APK instalable que bloquea anuncios y rastreadores por DNS con una VPN local (sin servidor externo), con registro, listas, permitidos/bloqueados propios, apps excluidas, DNS de salida, pausa, tile y arranque; medido en emulador en funcionalidad y consumo.

- [x] G1: los tests unitarios del nucleo (DNS, paquetes IP/UDP, listas, coincidencia, fuzz) pasan, al menos 20, cero fallos
  CHECK: node tools/run-unit-tests.mjs
  EXPECT: unit tests passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=39c410b0a8d2f52a70ab7d7e8f1005394a91d1c6d510985304c5dcde04f41a63; exit=0; EXPECT=matched; output-sha256=616934a7e6396d74f3d1507659d1274a819d46ca23072a0cff003454c4ae2786; output-bytes=331; shell=C:\WINDOWS\system32\cmd.exe; cwd=<repo>; path=3992e709f721/46 entries

- [x] G2: el APK release (minificado, firmado) se construye y pesa menos de 15 MB
  CHECK: node tools/check-apk.mjs
  EXPECT: apk check passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=65191b63f49e77d0f3d9d0fc403608d7e1df25b4f0d6a8dc9cb34c5d4e059d4a; exit=0; EXPECT=matched; output-sha256=8c4fe1e32ffdd34a3ee83aa99571fe28ec607dde7f3cf92a69fc1c7c9c5921ef; output-bytes=425; shell=C:\WINDOWS\system32\cmd.exe; cwd=<repo>; path=3992e709f721/46 entries

- [x] G3: en el emulador la VPN se levanta, los dominios de anuncios devuelven 0.0.0.0, los normales resuelven IP real, y permitir/bloquear propios cambia el resultado en vivo
  CHECK: node tools/device-dns-test.mjs
  EXPECT: device dns test passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=895191eb1f25485b10d55785931c578681ffe36add3cdbd155140e241ca5d47d; exit=0; EXPECT=matched; output-sha256=7d1e242743783639b3300e3fb7abab2de3b194c1fe9f959aadaad0d226e0f0fb; output-bytes=1038; shell=C:\WINDOWS\system32\cmd.exe; cwd=<repo>; path=3992e709f721/46 entries

- [x] G4: consumo dentro de presupuesto en el APK release: CPU en reposo < 1 s en 120 s, < 3 ms de CPU por consulta, PSS < 60 MB, ningun wakelock retenido
  CHECK: node tools/measure-consumption.mjs
  EXPECT: consumption within budget
  EVIDENCE: automatic-evidence=v1; definition-sha256=d8f7d8b45eb5933ceecab5a9709c8f745148b35be5e0080b5bb05f0d319d3d47; exit=0; EXPECT=matched; output-sha256=3faed6ec6601c555e5b66be6016fb6efab7b1df5fc7463b44017460e75da5aa1; output-bytes=627; shell=C:\WINDOWS\system32\cmd.exe; cwd=<repo>; path=3992e709f721/46 entries

- [x] G5: navegar funciona con la proteccion activa y una pagina de test de anuncios marca bloqueos (captura del navegador)
  EVIDENCE: Chrome en el emulador, adblock.turtlecute.org: sin DNShield 9/132 bloqueados (7 %), con DNShield 106/132 (80 %); capturas shots/chrome-off.png y shots/chrome-on.png (22/10/2026 15:23)

- [x] G6: la interfaz (inicio, registro, listas, ajustes) se ve bien y responde en el emulador (capturas revisadas)
  EVIDENCE: diseño aprobado por el usuario (estilo AdGuard con identidad propia); capturas shots/ag.jpg (4 pestañas) y shots/prot.png (Protección con 4 entradas como la referencia de AdGuard); interruptor de Bloqueo de anuncios verificado a mano (OFF deja pasar, ON bloquea)

- [x] G8: seguridad del APK release: superficie exportada minima, sin depuracion ni receptor de pruebas, datos privados, VPN solo enruta DNS, y resiste basura e inundacion desde otra app del movil
  CHECK: node tools/security-test.mjs
  EXPECT: security test passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=9c5b6b4cdad432c79dc84e2fccdad478691a77e3ff09697d23a32b0770351d9d; exit=0; EXPECT=matched; output-sha256=de775d68aa15bf122d10e9c76b37553c6472378c7e9405e8ba5fe3182ae598b1; output-bytes=1964; shell=C:\WINDOWS\system32\cmd.exe; cwd=<repo>; path=3992e709f721/46 entries

- [x] G7: entrega lista: APK y guia en espanol para el hermano en el Escritorio
  CHECK: node tools/check-handoff.mjs
  EXPECT: handoff ready
  EVIDENCE: automatic-evidence=v1; definition-sha256=200baa4fac54215dd591bd3bf5df5f88a8bc474f549fc84cb1d21c96d513efe2; exit=0; EXPECT=matched; output-sha256=2c6b670551e17a73401f8d8872d302f7b6fd34fda00c6ebd3eeae4313b601175; output-bytes=58; shell=C:\WINDOWS\system32\cmd.exe; cwd=<repo>; path=3992e709f721/46 entries
