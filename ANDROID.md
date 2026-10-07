# Android 0.2.0: Firebase

De app ontvangt de brugstatus via Firestore en meldingen via Firebase Cloud Messaging. Geen domein, tunnel, serveradres of open serverpoort nodig.

Volg **SERVER-SETUP.md** voor de volledige Arch- en Firebase-installatie.

1. Maak een eigen Firebase-project en registreer Android-package **nl.brugmonitor.app**.
2. Plaats google-services.json van deze app in **android/app/google-services.json**.
3. Activeer Firestore en publiceer de regels uit **firebase/firestore.rules**.
4. Configureer de server met de service-account-JSON van hetzelfde Firebase-project.
5. Bouw op Windows met **build-apk.cmd**.
6. Installeer **dist/Brugmonitor-debug.apk**.
7. Tik op **Meldingen inschakelen**, sta Android-meldingen toe, en verstuur de servertest uit het stappenplan.

De JSON voor het service-account gaat alleen naar de server; hij hoort niet in de APK.

Een controlebuild zonder Firebase-configuratie kan worden gemaakt met:
`powershell -File scripts/build-apk.ps1`
Deze app geeft duidelijk aan dat Firebase nog moet worden ingesteld en heeft geen live status of push. De gewone build-apk.cmd vereist een geldige appconfiguratie.

De APK is een debugbuild voor eigen testen. Android-package nl.brugmonitor.app, minimaal Android 8, versie 0.2.0. Voor publicatie is later een vaste release-signingconfiguratie nodig.

De Firebase-topicnaam en het Firestore-document zijn vaste gedeelde afspraken tussen server en app:
- topic: brugmonitor-sas-van-gent-v1
- document: bridges/sas-van-gent
- notification channel: bridge_status

De app bewaart de meldingskeuze, abonneert na identifierwijzigingen opnieuw, vraagt op Android 13+ toestemming en opent bij een melding de actuele Firestore-status.
