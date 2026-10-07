BRUGMONITOR - FIREBASE EN ARCH LINUX
=================================

Server leest Brug-open -> Firestore -> Android-app.
Bij OPEN/DICHT-wisselingen verstuurt de server Firebase Cloud Messaging-push.

Geen domein, tunnel, open serverpoort of serveradres in de app nodig.

Volledige installatie: SERVER-SETUP.md
Android-instructies: ANDROID.md
Serverpakket: dist/Brugmonitor-server.zip

ARCH
----
Pak het serverpakket uit in /projects/Brugmonitor.
Stel .env en de aparte Firebase-service-account-JSON in.
Voer uit: bash deploy/install-user-service.sh

ANDROID
-------
Download google-services.json voor Android-package nl.brugmonitor.app.
Plaats het in android/app/.
Bouw met build-apk.cmd; installeer dist/Brugmonitor-debug.apk.

LOKALE WEBVERSIE
---------------
Installeer requirements.txt in een eigen Python-venv.
Start python server.py (zonder .env standaard http://127.0.0.1:8080).
Browsermeldingen werken alleen zolang de pagina actief is.
De Android-app gebruikt nu Firebase in plaats van een LAN-serveradres.
