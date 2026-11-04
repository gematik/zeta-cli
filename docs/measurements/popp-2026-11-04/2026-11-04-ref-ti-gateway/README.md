# PoPP-Messung TI-Gateway: ti-gateway

- **Datum:** 2026-11-04
- **Konnektor:** `ti-gateway.kon`, Produkttyp Konnektor 6.0.2, Vendor `vendor`, Code IK, Firmware 2.0.1, RTT rund 19 ms
- **Art:** TI-Gateway
- **Karten:** eGK `EGK-5`, SMC-B `SMC-B-3`, beide am selben Terminal; Handle direkt übergeben, also kein `getCards`
- **Client:** `zeta` 0.15.0, SDK 1.3.2, macOS, `zeta popp connector`, jeder Lauf ein neuer Prozess
- **Service:** `popp.ref.poppservice.de`, ein einzelner Client, Last auf REF unbekannt

## Gesamtdauer in ms

| Fall | Gesamt | `popp.flow` | Läufe |
|---|---|---|---|
| Token gültig (Median) | 3471 | 2440 | 10 |
| Profil gelöscht, Läufe direkt nacheinander | 7411, 7534 | 6320, 6480 | 2 |
| Profil gelöscht, nach einer Pause | 12823 | 11510 | 1 |
| Erster Lauf überhaupt | 13804 | 12680 | 1 |

## Phasen bei gültigem Token (Median, n=10)

| Phase | ms |
|---|---|
| JVM | 356 |
| `sdk.init` | 345 |
| SDS laden (`connector.connect`) | 237 |
| `startCardSession` | 193 |
| Discover, Token, DPoP, WebSocket-Aufbau | 462 |
| `secureSendApdu`, 4 Runden | 1154 |
| Service: 4 Szenarien + Token | 202 + 64 |
| `stopCardSession` (Fehler 4288) | 67 |

`secureSendApdu` je Runde, typisch: rund 136, 243, 208, 550 ms.

## Service-Antworten in den langsamen Läufen (ms)

| Lauf | Szenario 1–4 | Token |
|---|---|---|
| Erster Lauf | 154, 2090, 157, 58 | 3730 |
| `cold0` | 1490, 1270, 157, 130 | 1670 |
| `cold1` | 36, 32, 92, 40 | 58 |
| `cold2` | 91, 40, 58, 39 | 179 |

Ursache offen.

## Dateien

- `raw/`: `warm`, `first`, `cold0`–`cold2` als JSON, dazu Logs mit geschwärzten JWTs.
- `scripts/`: Messskripte, die Pfade darin zeigen auf das temporäre Verzeichnis der Sitzung.
