# PoPP-Messung PC/SC: Identive CLOUD 4700 F (kontaktbehaftet)

- **Datum:** 2026-11-04
- **Reader:** `Identive Identive CLOUD 4700 F Dual Interface Reader 01` (USB 0x04E6:0x5720)
- **Karte:** echte eGK, kontaktbehaftet, ATR `3BD097FF81B1FE451F072B`
- **Client:** `zeta` 0.15.0, SDK 1.3.2, macOS, `zeta popp standard`, jeder Lauf ein neuer Prozess
- **Service:** `popp.ref.poppservice.de`, ein einzelner Client, Last auf REF unbekannt
- **Auth:** SMC-B lokal per `p12`, kein Konnektor

## Gesamtdauer (Median in ms)

| Fall | Gesamt | `popp.flow` | Läufe |
|---|---|---|---|
| Token gültig | 4018 | 3040 | 10 |
| Profil gelöscht | 6041 | 5140 | 3 |
| Erster Lauf mit Profil vom Vortag | 9500 | 7720 | 1 |

Der erste Lauf enthielt ein `400` vom Auth-Server („Client hat keine Berechtigung auf angef. Resource“) und eine Token-Antwort von 816 ms. Ursache offen.

Zum Vergleich Karten-Image (kartos), Token gültig, n=12: gesamt 2079, `popp.flow` 932.

## Phasen bei gültigem Token (Median, n=5, mit `-vv`)

| Phase | ms |
|---|---|
| JVM, `sdk.init`, Karte öffnen | 1002 |
| Discover, Registrierung, Token aus Cache, DPoP | 138 |
| WebSocket-Aufbau | 502 |
| Karte, 10 APDUs in 4 Runden | 2076 |
| Service, WebSocket und Rest | ca. 245 |

## APDUs (Median, n=5)

| Runde | Header | Kommando (Bytes) | Antwort (Bytes) | ms |
|---|---|---|---|---|
| 1 | `00A4040C` | 12 | 2 | 25.8 |
| 1 | `00B09100` | 5 | 47 | 60.5 |
| 2 | `00B08700` | 5 | 222 | 211.3 |
| 2 | `00B08600` | 5 | 224 | 212.7 |
| 2 | `80CA0100` | 7 | 222 | 243.9 |
| 3 | `002241A4` | 11 | 2 | 28.3 |
| 3 | `10860000` | 22 | 71 | 165.4 |
| 4 | `00860000` | 74 | 2 | 158.4 |
| 4 | `0CA4040C` | 28 | 16 | 90.2 |
| 4 | `0CB08400` | 23 | 904 | 879.8 |

## Einordnung

- Rund 0,98 ms pro Byte erklären etwa 1,87 s der 2,08 s (1904 Bytes). Das ist abgeleitet, nicht direkt gemessen.
- Vermutung, nicht geprüft: Der Leser handelt die im ATR angekündigte höhere Rate (`TA1=97`) nicht aus.
- Die Vorgabe „unter 1 Sekunde“ netto für Kartenoperationen wird mit diesem Leser nicht erreicht.
- Die APDU-Zeiten stammen aus Zeitstempeln auf den `-vv`-Zeilen, die Logausgabe kann sie leicht erhöhen.

## Dateien

- `raw/`: `warm`, `timed`, `first`, `cold0`–`cold2` als JSON, dazu Logs. APDUs nur als Header und Statuswort, JWTs geschwärzt.
- `scripts/`: Messskripte, die Pfade darin zeigen auf das temporäre Verzeichnis der Sitzung.
