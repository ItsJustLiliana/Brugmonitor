# Brugmonitor

Live status van de Sas van Gent brug, met een Android-app en Firebase-pushmeldingen.

De Arch Linux-server leest Brug-open met headless Chromium en publiceert de status naar Firestore. De Android-app leest Firestore rechtstreeks. Bij OPEN â†” DICHT verstuurt de server een melding via Firebase Cloud Messaging. Er is geen domein of inkomende serverpoort nodig.

## Installatie

Volg [SERVER-SETUP.md](SERVER-SETUP.md) voor Firebase, de Arch-gebruikersservice, de Android-build en de testmelding. Zie ook [ANDROID.md](ANDROID.md).

Maak een eigen Firebase-project en configureer beide onderdelen:
- Android: download google-services.json voor package nl.brugmonitor.app en plaats dit in android/app/.
- Server: bewaar de private service-account-JSON buiten deze repository en stel het pad in via je eigen .env.

De lokale configuratie, sleutels, Python-venv, runtimegegevens en buildproducten staan in .gitignore. De Gradle-wrapper, .env.example en Firestore-regels horen wel in Git.

## Ontwikkeling

Gebruik Python 3.10 of nieuwer en een eigen virtuele omgeving:

```bash
python -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m unittest discover -s tests -v
python server.py
```

Op Windows gebruik je `.venv\Scripts\Activate.ps1` in plaats van de source-opdracht.

Zonder .env draait de lokale webversie op http://127.0.0.1:8080. De tests gebruiken mocks en versturen geen echte Firebase-meldingen.

Bouw de Android-app op Windows met build-apk.cmd, nadat de Firebase-appconfiguratie is geplaatst. Maak een overdraagbaar serverpakket met:

```bash
python scripts/package-server.py
```

De pakketten verschijnen in dist/ en worden niet gecommit. De APK is momenteel een debugbuild voor eigen testen.

Automatisch de server bijwerken en app-updates via je website: zie [AUTOMATION.md](AUTOMATION.md).
