# PoPP-Performance gegen REF

Messungen vom 2026-11-04 zur Frage, warum ein PoPP-Token beim Kunden rund 8 s dauert.

## Ergebnis

- **Token gültig:** 2,1 s mit Karten-Image, 4,0 s mit PC/SC-Leser, 3,5 s mit TI-Gateway.
- **Ohne gültiges Token:** 3,9 s, 6,0 s und rund 7,5 s. Hier liegt das TI-Gateway nahe an den 8 s des Kunden.
- **Größter Block mit echter Karte:** die APDUs, 2,08 s am Leser und 1,15 s über das TI-Gateway.
- **Service:** in der Regel rund 0,2 bis 0,3 s pro Mint, einzelne Antworten aber bis 3,7 s. Die Ursache ist offen.

## Aufbau

| | Karten-Image | PC/SC | TI-Gateway |
|---|---|---|---|
| Kartenzugang | `kartos`, `EGKG2_1_ICCSN-EGK-IMAGE_RU_mobil` | Identive CLOUD 4700 F, kontaktbehaftet | `ti-gateway`, Konnektor 6.0.2, Vendor `vendor` |
| Befehl | `zeta popp kartos` | `zeta popp standard` | `zeta popp connector` |
| SMC-B | lokal per `p12` | lokal per `p12` | über das TI-Gateway |
| APDUs | 18 in 4 Runden, mit 4 CVC-Importen | 10 in 4 Runden | 4 Runden, laut Größen wie bei PC/SC |

Für alle gilt: `zeta` 0.15.0, SDK 1.3.2, macOS, `popp.ref.poppservice.de`, ein einzelner Client, jeder Lauf ein neuer Prozess. Die Last auf REF ist unbekannt.

## Gesamtdauer in ms

| Fall | Karten-Image | PC/SC | TI-Gateway |
|---|---|---|---|
| Token gültig, gesamt (Median) | 2079 (n=12) | 4018 (n=10) | 3471 (n=10) |
| Token gültig, `popp.flow` (Median) | 932 | 3040 | 2440 |
| Profil gelöscht, gesamt | 3861 (Median, n=3) | 6041 (Median, n=3) | 7411, 7534, 12823 |
| Erster Lauf mit diesem Profil | nicht einzeln ausgewertet | 9500 (Profil vom Vortag) | 13804 (neues Profil) |

## Ablauf

JVM-Start ist nicht Teil der Betrachtung, das SDK läuft im Daemon. Gemessen wurde bisher aber nur `zeta popp` mit neuem Prozess. Alle Werte sind deshalb Median in ms aus einer kalten JVM und im Daemon vermutlich niedriger.

### 1. Vorbereitung (einmalig)

| Schritt | Karten-Image | PC/SC | TI-Gateway |
|---|---|---|---|
| SDS laden | – | – | 237 |
| Service Discovery (Katalog) | nicht gemessen | nicht gemessen | nicht gemessen |
| SDK je Dienst (`sdk.init`) | 580 | 462 | 345 |
| ZETA Discovery, erstmalig | nicht getrennt | nicht getrennt | nicht getrennt |

### 2. Login (je Dienst, dann periodisch)

| Schritt | Karten-Image | PC/SC | TI-Gateway |
|---|---|---|---|
| Erster Login, Mehraufwand gegenüber gültigem Token | 1799 | 2105 | rund 3960 |
| Periodischer Token-Refresh | nicht gemessen | nicht gemessen | nicht gemessen |

### 3. Hauptpfad (je Token)

| Schritt | Karten-Image | PC/SC | TI-Gateway |
|---|---|---|---|
| Karte lesbar | 14 | 25 | nicht gemessen |
| `startCardSession` | – | – | 193 |
| WebSocket öffnen, mit Prüfung und DPoP | 659 (mit Start von `kartos`) | 640 | 462 |
| **APDU-Tanz: Karte oder Konnektor** | **4,5** | **2076** | **1154** |
| APDU-Tanz: Service und Netz | 218 | 294 | 245 |
| `stopCardSession` | – | – | 67 |
| **Hauptpfad (`popp.flow` ohne SDS)** | **932** | **3040** | **2203** |

- Die Werte stammen teils aus getrennten Reihen und addieren sich nicht exakt.
- „Karte lesbar“ ist bei Karten-Image und PC/SC `popp.flow` minus `popp.connect`, enthält also auch das Schließen.
- Am TI-Gateway wurde die eGK-Kennung direkt übergeben, also ohne `getCards`.
- Am TI-Gateway enthält der Kartenanteil HTTP und die Signaturprüfung am Konnektor, ist also keine reine Kartenzeit.

### APDU-Tanz je Szenario

| Szenario | APDUs (Image / echte Karte) | Service und Netz: Image | Service und Netz: PC/SC | Service und Netz: TI-Gateway | Karte: PC/SC | `secureSendApdu`: TI-Gateway |
|---|---|---|---|---|---|---|
| 1 | 2 / 2 | 38 | 40 | 40 | 86 | 136 |
| 2 | 3 / 3 | 30 | 84 | 31 | 668 | 243 |
| 3 | 10 / 2 | 58 | 66 | 56 | 194 | 208 |
| 4 | 3 / 3 | 42 | 44 | 54 | 1128 | 550 |
| Token | – | 50 | 60 | 64 | – | – |

## Karte im Detail

### PC/SC, Identive CLOUD 4700 F

| Runde | Header | Kommando (Bytes) | Antwort (Bytes) | ms |
|---|---|---|---|---|
| 1 | `00A4040C` | 12 | 2 | 26 |
| 1 | `00B09100` | 5 | 47 | 61 |
| 2 | `00B08700` | 5 | 222 | 211 |
| 2 | `00B08600` | 5 | 224 | 213 |
| 2 | `80CA0100` | 7 | 222 | 244 |
| 3 | `002241A4` | 11 | 2 | 28 |
| 3 | `10860000` | 22 | 71 | 165 |
| 4 | `00860000` | 74 | 2 | 158 |
| 4 | `0CA4040C` | 28 | 16 | 90 |
| 4 | `0CB08400` | 23 | 904 | 880 |

Rund 0,98 ms pro Byte erklären etwa 1,87 s der 2,08 s. Das ist aus den Messwerten abgeleitet, nicht direkt gemessen.

### TI-Gateway, `secureSendApdu` je Runde

| Runde | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| ms (typisch) | 136 | 243 | 208 | 550 |

## Langsame Läufe

Hier wartete der Client auf den Service, nicht auf Karte oder TI-Gateway.

| Lauf (TI-Gateway) | Service, Szenario 1–4 (ms) | Token (ms) | Gesamt (ms) |
|---|---|---|---|
| Erster Lauf, neues Profil | 154, 2090, 157, 58 | 3730 | 13804 |
| Profil gelöscht, nach Pause | 1490, 1270, 157, 130 | 1670 | 12823 |
| Profil gelöscht, direkt danach | 36, 32, 92, 40 | 58 | 7411 |
| Profil gelöscht, direkt danach | 91, 40, 58, 39 | 179 | 7534 |

Weitere Auffälligkeiten:

- **PC/SC, erster Lauf:** `400` vom Auth-Server („Client hat keine Berechtigung auf angef. Resource“), Token-Antwort nach 816 ms, gesamt 9500 ms.
- **Karten-Image:** drei Ausreißer in 10 Läufen mit Wrapper: Service-Antworten nach 1450 und 1170 ms sowie einmal die erste APDU erst nach 4763 ms.

### Pausentest

TI-Gateway, Token gültig, neuer Prozess. Vergleich: Median ohne Pause.

| Pause | Gesamt | JVM | Service und Netz | Login-Anteil | `secureSendApdu` |
|---|---|---|---|---|---|
| keine (Median, n=10) | 3471 | 356 | 284 | 462 | 1154 |
| 60 s | 4770 | 840 | 801 | 467 | 1289 |
| 120 s | 4543 | 1033 | 528 | 477 | 1132 |
| 300 s | 4933 | 843 | 246 | 1386 | 1123 |
| 60 s | 5041 | 831 | 1163 | 565 | 1148 |
| 120 s | 4158 | 818 | 279 | 436 | 1123 |
| 300 s | Abbruch | – | – | – | – |

- Der Mehraufwand nach einer Pause liegt vor allem am JVM-Start (rund 500 ms) und einzelnen Service-Antworten.
- Wartezeiten von mehreren Sekunden beim Service traten nicht auf. Der Verdacht auf einen Leerlauf-Effekt ist damit nicht bestätigt.
- Der letzte Lauf brach ab: `connector.sds` am TI-Gateway lief nach 10 s in einen Timeout.
- Der Login-Anteil von 1386 ms kam ohne `AuthSignatureService` zustande. Die Ursache ist offen.

## Einordnung

Belegt:

- Mit einer gültigen Session bleibt vom Service im Normalfall wenig Wartezeit.
- Ein neuer Login kostet am TI-Gateway rund 4 s zusätzlich. Die Konnektor-Aufrufe erklären davon rund 690 ms.
- Die APDU-Dauer am Identive-Leser folgt fast nur der Datenmenge.

Vermutet, nicht geprüft:

- Der Identive-Leser handelt die im ATR angekündigte höhere Rate (`TA1=97`) nicht aus.
- Der Rest des Logins entfällt auf den Auth-Server. Der Trace schlüsselt ihn nicht auf.

Berechnet, nicht gemessen:

- Eine warme Session mit wiederverwendeter Konnektor-Session würde am TI-Gateway rund 4 s Login, 0,7 s JVM und `sdk.init` und rund 0,2 s SDS sparen.

## Grenzen

- Die Läufe sind sequenziell und ohne Last. Aussagen zu Lastspitzen sind daraus nicht möglich.
- `zeta serve` wurde nicht gemessen, alle Werte stammen aus einer kalten JVM.
- SDK 2.0.1 wurde nicht gemessen.
- Ob am TI-Gateway eine echte oder simulierte Karte steckt, ist nicht geprüft.
- Karten mit CVC-Import wurden nur im Simulator gesehen.

## Offen

- Einboxkonnektor messen.
- Hauptpfad und Refresh im Daemon messen.
- Gegenlauf mit einem anderen Leser, um die Vermutung zur Übertragungsrate zu prüfen.

## Rohdaten

Für die Veröffentlichung sind Adresse, Name und Hersteller des TI-Gateways, ICCSNs, Session-IDs und lokale Pfade durch Platzhalter ersetzt. Die Skripte laufen deshalb nicht unverändert. Die Messwerte sind nicht verändert.

- [Karten-Image](2026-11-04-ref-kartos-EGKG2_1_ICCSN-EGK-IMAGE_RU_mobil/README.md)
- [PC/SC, Identive CLOUD 4700 F](2026-11-04-ref-pcsc-identive-cloud-4700f-contact/README.md)
- [TI-Gateway, `ti-gateway`](2026-11-04-ref-ti-gateway/README.md)
