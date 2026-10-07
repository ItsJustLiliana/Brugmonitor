# Brugmonitor op Arch Linux, zonder domein

Je Arch-server leest Brug-open met headless Chromium. Hij schrijft de laatste status naar **Firestore** en verstuurt bij OPEN ↔ DICHT een melding via **Firebase Cloud Messaging (FCM)**. De Android-app leest Firestore rechtstreeks. De server heeft alleen uitgaande internettoegang nodig. Geen domein, Cloudflare Tunnel, Nginx of open inkomende poort.

De code gebruikt dezelfde opzet als StickStat: `/projects/Brugmonitor` en een systemd-gebruikersservice. De lokale HTTP-server op `127.0.0.1:8081` is alleen voor diagnose op de server.

## 1. Maak een eigen Firebase-project

Open https://console.firebase.google.com/ en maak een **apart project voor Brugmonitor**. Google Analytics is voor deze app niet nodig. Gebruik hetzelfde Google-account als voor StickStat, maar houd het project apart: de meegeleverde Firestore-regels vervangen de regels van dat project.

Voeg een Android-app toe:

- Android-package: **nl.brugmonitor.app** (precies zo).
- Naam: Brugmonitor.
- Een SHA-1-certificaat is voor deze Firestore/FCM-opzet niet nodig.
- Download **google-services.json**.

Plaats dit bestand op je Windows-pc in:

```text
C:\Users\marij\Projects\Brugmonitor\android\app\google-services.json
```

Dit is de configuratie voor de app. Gebruik geen google-services.json van `nl.stickstat.app`.

## 2. Activeer Firestore en zet de leesregels klaar

In het nieuwe Firebase-project:

1. Open **Build → Firestore Database → Create database**.
2. Kies de Standard-editie en de standaarddatabase `(default)`.
3. Kies een Europese locatie.
4. Start in **Production mode**, niet tijdelijk test mode.
5. Open het tabblad **Rules**.
6. Vervang de regels door de volledige inhoud van **firebase/firestore.rules** uit dit project en klik **Publish**.

De regels zijn ook hieronder opgenomen:

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /bridges/sas-van-gent {
      allow read: if true;
      allow write: if false;
    }
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

Alleen de openbare brugstatus is leesbaar. Android-clients kunnen niets schrijven. De server schrijft met zijn Admin SDK-account; die gebruikt IAM en valt buiten deze clientregels. Je hoeft het document niet handmatig te maken. De server maakt `bridges/sas-van-gent` bij zijn eerste succesvolle controle.

Er is geen Authentication, Realtime Database of Cloud Function nodig. Controleer je gebruik in de Firebase-console. De heartbeat is standaard 30 seconden; bij onveranderde status zijn dat ongeveer 2.880 writes per dag, plus updates wanneer de tekst/status/geschiedenis verandert. Actieve telefoons lezen deze updates. De app sluit de statuslistener wanneer hij naar de achtergrond gaat.

## 3. Download de serversleutel

In hetzelfde Firebase-project:

1. Open **Project settings → Service accounts**.
2. Kies **Generate new private key** en download de JSON.
3. Bewaar deze als `firebase-service-account.json` op je server in `~/.config/brugmonitor/`.

Dit is een ander bestand dan google-services.json. De private service-account-JSON hoort uitsluitend op de server; hij wordt niet in de APK of het serverpakket opgenomen.

Voorbeeld vanaf Windows, met de host uit je StickStat-instructies:

```powershell
scp "C:\pad\naar\brugmonitor-firebase-adminsdk.json" marijn@archlinux.tail50bfa9.ts.net:~/brugmonitor-firebase-service-account.json
```

Op de Arch-server, als je gewone gebruiker die ook StickStat draait:

```bash
mkdir -p ~/.config/brugmonitor
chmod 700 ~/.config/brugmonitor
mv ~/brugmonitor-firebase-service-account.json ~/.config/brugmonitor/firebase-service-account.json
chmod 600 ~/.config/brugmonitor/firebase-service-account.json
```

Gebruik voor zowel appconfiguratie als serversleutel hetzelfde nieuwe Firebase-project.

## 4. Upload de servercode en installeer Arch-packages

Het pakket **dist/Brugmonitor-server.zip** bevat uitsluitend de benodigde code, scripts en instructies. Geen echte sleutels of lokale configuratie.

Op Windows:

```powershell
scp "C:\Users\marij\Projects\Brugmonitor\dist\Brugmonitor-server.zip" marijn@archlinux.tail50bfa9.ts.net:~/Brugmonitor-server.zip
```

Op Arch:

```bash
sudo pacman -Syu --needed python python-pip chromium unzip
sudo install -d -o "$USER" -g "$(id -gn)" /projects/Brugmonitor
unzip ~/Brugmonitor-server.zip -d /projects/Brugmonitor
cd /projects/Brugmonitor
cp .env.example .env
chmod 600 .env
sed -i "s|^GOOGLE_APPLICATION_CREDENTIALS=.*|GOOGLE_APPLICATION_CREDENTIALS=$HOME/.config/brugmonitor/firebase-service-account.json|" .env
```

Arch levert Chromium en chromedriver samen. Je hoeft geen ChromeDriver-versie apart te downloaden. Controleer:

```bash
/usr/bin/chromium --version
/usr/bin/chromedriver --version
```

De `.env` gebruikt standaard:

```dotenv
CHROME_BINARY=/usr/bin/chromium
CHROMEDRIVER_PATH=/usr/bin/chromedriver
CHROME_NO_SANDBOX=1
FIREBASE_ENABLED=1
FIRESTORE_HEARTBEAT_SECONDS=30
BRUGMONITOR_HOST=127.0.0.1
BRUGMONITOR_PORT=8081
```

De service draait als je gewone gebruiker. Chromium wordt headless gestart met de bestaande Selenium-opzet. `CHROME_NO_SANDBOX=1` zorgt dat Chromium ook binnen deze systemd-service kan starten; de service heeft daarnaast `NoNewPrivileges` en een eigen tijdelijke directory.

## 5. Installeer en start de gebruikersservice

Nog steeds als de gebruiker die ook StickStat draait:

```bash
cd /projects/Brugmonitor
bash deploy/install-user-service.sh
sudo loginctl enable-linger "$USER"
```

Het installatiescript maakt een eigen Python-venv, installeert dependencies, draait de tests, schrijft de unit naar `~/.config/systemd/user/` en start `brugmonitor.service`.

`enable-linger` laat gebruikersservices ook na uitloggen en na een reboot draaien. Dit is mogelijk al ingesteld voor StickStat.

Controleer:

```bash
systemctl --user status brugmonitor.service
journalctl --user -u brugmonitor.service -f
curl http://127.0.0.1:8081/api/health
```

De eerste status kan even duren, omdat Chromium eerst de bronpagina moet laden. Na een succesvolle Firestore-write zie je `firestore_publish_ok: true`. In Firebase verschijnt het document `bridges/sas-van-gent`.

## 6. Bouw en installeer de Firebase-app

Zorg eerst dat de juiste google-services.json op je Windows-pc in `android/app/` staat. Open PowerShell in het Brugmonitor-project:

```powershell
cd C:\Users\marij\Projects\Brugmonitor
.\build-apk.cmd
```

De build controleert het Android-package, compileert de app, voert Android lint uit en schrijft:

```text
dist\Brugmonitor-debug.apk
```

Installeer deze APK op je telefoon. De vorige LAN-appversie moet worden bijgewerkt. Je hoeft in deze nieuwe versie geen serveradres in te vullen.

Open de app:

1. De brugstatus komt vanzelf uit Firestore.
2. Tik op **Meldingen inschakelen**.
3. Sta Android-meldingen toe als Android daarom vraagt.
4. Wacht tot de app aangeeft dat meldingen actief zijn.
5. Zet de app naar de achtergrond.

Google Play-services en internet zijn nodig voor FCM. Voor een emulator gebruik je een image met Google Play. Meldingen werken wanneer de app normaal op de achtergrond staat of uit recente apps is weggeveegd. Na Android **Forceer stoppen** moet je de app opnieuw openen. Android-instellingen, internet en batterijbeheer kunnen levering vertragen.

## 7. Test de melding vanaf Arch

Valideer eerst de serversleutel en FCM-toegang zonder een melding af te leveren:

```bash
cd /projects/Brugmonitor
.venv/bin/python scripts/send-test-push.py --dry-run
```

Verstuur daarna een echte testmelding naar alle telefoons die Brugmonitor-meldingen hebben ingeschakeld:

```bash
.venv/bin/python scripts/send-test-push.py
```

Je telefoon moet **Brugmonitor testmelding** ontvangen. De test verandert de brugstatus niet. Daarna verstuurt de monitor automatisch meldingen bij OPEN ↔ DICHT. Het gedeelde topic is `brugmonitor-sas-van-gent-v1`; je hoeft dat niet vooraf in Firebase aan te maken.

De eerste succesvolle meting na het starten van de monitor is een baseline en veroorzaakt geen melding. Herhaalde metingen van dezelfde status geven geen extra push. Een fout bij uitlezen behoudt de laatst bekende brugstatus.

## Onderhoud en problemen

**Nieuwe servercode uploaden:** pak een nieuw serverpakket uit in dezelfde map. Laat je eigen `.env` en `~/.config/brugmonitor/` staan. Voer daarna uit:

```bash
cd /projects/Brugmonitor
bash deploy/update.sh
```

**Geen live status in de app:** controleer `journalctl`, de Firestore-regels, het Firebase-project van beide JSON-bestanden en of `bridges/sas-van-gent` is aangemaakt. Bij `PERMISSION_DENIED` in Android ontbreken meestal de leesregels. Bij een serverfout kan ook de database ontbreken of de service-account onvoldoende IAM-rechten hebben.

**FCM 403/API disabled:** controleer in de Google Cloud-console van dit project of **Firebase Cloud Messaging API** ingeschakeld is en de service-account meldingen mag versturen. Er wordt de moderne Admin SDK gebruikt; geen legacy server key.

**Status verouderd:** de app toont een waarschuwing bij gecachte/offline data of wanneer de laatste succesvolle broncontrole ouder is dan ongeveer 90 seconden. Een stilgevallen server blijft zo niet onbeperkt als 'live' weergegeven.

**Push bij netwerkstoring:** de server bewaart de laatste melding in SQLite en probeert kort opnieuw. Een nieuwe brugstatus vervangt een nog niet verstuurde oude melding. Na een herstart wacht de pushworker eerst op een verse bronstatus. Een overgebleven melding voor de verkeerde status wordt weggegooid. Meldingen verlopen na twee minuten; oude OPEN-meldingen worden niet lang daarna alsnog afgeleverd. FCM-aanvaarding is geen bewijs van ontvangst op iedere telefoon. Herhaling is mogelijk bij een netwerkfout nadat Firebase een bericht al heeft aangenomen.

**Lokale webversie:** `python server.py` zonder `.env` blijft beschikbaar op `127.0.0.1:8080`. Installeer eerst `requirements.txt`; de applicatie installeert niet meer zelf packages tijdens het starten. De browserknop geeft alleen browsermeldingen zolang de pagina actief is. Achtergrondpush is in dit pakket voor de Android-app.

## Bronnen

- Firebase Android configuratie: https://firebase.google.com/docs/android/setup
- Firebase Admin SDK: https://firebase.google.com/docs/admin/setup
- Firestore setup: https://firebase.google.com/docs/firestore/quickstart
- Firestore security rules: https://firebase.google.com/docs/firestore/security/get-started
- Topic subscriptions: https://firebase.google.com/docs/cloud-messaging/manage-topic-subscriptions
- Android ontvangen en achtergrondgedrag: https://firebase.google.com/docs/cloud-messaging/android/receive-messages
- Arch Chromium-bestanden: https://archlinux.org/packages/extra/x86_64/chromium/files/
