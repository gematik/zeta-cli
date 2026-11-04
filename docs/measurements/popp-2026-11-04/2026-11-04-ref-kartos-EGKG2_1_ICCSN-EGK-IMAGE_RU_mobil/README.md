# PoPP-Messung Karten-Image (kartos)

- **Datum:** 2026-11-04
- **Reader:** keiner, Simulator `kartos`
- **Image:** `EGKG2_1_ICCSN-EGK-IMAGE_RU_mobil.xml`
- **Client:** `zeta` 0.15.0, SDK 1.3.2, macOS, `zeta popp kartos`, jeder Lauf ein neuer Prozess
- **Service:** `popp.ref.poppservice.de`, ein einzelner Client, Last auf REF unbekannt
- **Auth:** SMC-B lokal per `p12`, kein Konnektor

## Gesamtdauer (Median in ms)

| Phase | Profil gelöscht, n=3 | Token gültig, n=12 |
|---|---|---|
| Gesamt | 3861 | 2079 |
| JVM-Start | 399 | 424 |
| `sdk.init` | 604 | 580 |
| `popp.flow` | 2720 | 932 |
| WebSocket: 4 Szenarien | 183 | 172 |
| WebSocket: Token | 46 | 50 |
| Login, WebSocket-Aufbau, Kartenschleife | 2458 | 659 |

Die Mediane addieren sich nicht exakt.

## Karte (Reihe mit Zeitmess-Wrapper, n=10)

- 18 APDUs in 4 Runden (2, 3, 10, 3), darin 4 CVC-Importe.
- Karte netto: Median 4,5 ms (2,4 bis 10,3), längste einzelne APDU 0,9 ms.
- Erste APDU im Median 734 ms nach Start von `kartos`.
- Der Wrapper verlängert den Lauf: gesamt Median 2391 ms (2066 bis 6581).

## Ausreißer

Ursache offen, der Trace hat keine Spans innerhalb von `popp.connect`.

- `card`, Lauf 3: Antwort auf Szenario 3 nach 1450 ms statt rund 50 ms.
- `card`, Lauf 6: Antwort auf Szenario 2 nach 1170 ms.
- `card`, Lauf 4: erste APDU erst nach 4763 ms, gesamt 6581 ms.

## Dateien

- `raw/`: `warm` (Token gültig), `cold` und `cold0`–`cold2` (Profil gelöscht), `card` (mit Wrapper), jeweils JSON und Logs. `card.ndjson` enthält pro Lauf die APDUs mit Header und Statuswort.
- `scripts/`: Messskripte und Wrapper `kartos-proxy`, die Pfade darin zeigen auf das temporäre Verzeichnis der Sitzung.
