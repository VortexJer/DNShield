# Avisos de terceros

DNShield es software libre bajo la licencia GPL-3.0 (ver `LICENSE`). Es un proyecto personal sin ánimo de lucro.
No está afiliado ni respaldado por AdGuard ni por ningún proveedor de DNS.

La app incluye o descarga datos públicos de terceros, cada uno con su licencia:

| Dato | Fuente | Licencia |
|---|---|---|
| `app/src/main/assets/lists/adguard.txt` (AdGuard DNS filter, copia incluida) | [AdguardTeam/AdGuardSDNSFilter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | GPL-3.0 |
| `app/src/main/assets/filters.json` (catálogo de listas DNS) | [AdguardTeam/HostlistsRegistry](https://github.com/AdguardTeam/HostlistsRegistry) | GPL-3.0 |
| `app/src/main/assets/companies.tsv` (dominio → empresa, derivado) | [AdguardTeam/companiesdb](https://github.com/AdguardTeam/companiesdb) | CC BY-SA 4.0 |
| Listas que el usuario activa desde la app (HaGeZi, OISD, StevenBlack, URLHaus, PhishTank…) | Sus autores, vía HostlistsRegistry | La de cada lista |

`companies.tsv` es una adaptación (solo dominio, nombre y categoría) de companiesdb y se distribuye bajo la misma
licencia CC BY-SA 4.0, con atribución a AdGuard Software Ltd. y a los colaboradores de companiesdb.

Los servidores DNS públicos (Cloudflare, Quad9, Google, AdGuard DNS, OpenDNS, CleanBrowsing, Control D, Mullvad,
DNS.SB, Comodo, AliDNS) son servicios de sus respectivos proveedores; sus nombres se usan solo para identificarlos.
"AdGuard" y "AdGuard VPN" son marcas de AdGuard Software Ltd.; la app solo enlaza a su app oficial.
