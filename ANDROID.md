# Android 0.3.3

De app krijgt de brugstatus via Firestore en meldingen via Firebase Cloud Messaging. Volg SERVER-SETUP.md voor de Arch-server en Firebase-configuratie.

1. Plaats google-services.json voor nl.brugmonitor.app in android/app/google-services.json.
2. Bouw lokaal met **build-apk.cmd**.
3. Installeer **dist/Brugmonitor-debug.apk** over je bestaande app.
4. Schakel meldingen in en geef Android toestemming.

Pas de versie op een plek aan: **app_config.json** (version en buildNumber). Verhoog buildNumber bij iedere release. Publiceren op liliananuzohra.com en de automatische serverworkflow staan in **AUTOMATION.md**. De APK bouw je zelf.

De app toont updates boven de Details-dropdown, downloadt de APK en opent de Android-installer na controle. Gebruik dezelfde ondertekeningssleutel voor iedere build.

Meldingen verschijnen niet bovenaan? Open **Details → Meldingen op scherm instellen** en activeer geluid en **Weergeven als pop-up** of **Op scherm tonen** voor Brugstatus. Android bewaart eerdere kanaalinstellingen na een update. Nieuwere brugmeldingen vervangen de vorige, zowel met geopende als gesloten app.

Firebase-afspraken: topic brugmonitor-sas-van-gent-v1, document bridges/sas-van-gent, kanaal bridge_status. De Firebase-serverkey hoort alleen op de server. De app vraagt op Android 13+ meldingstoestemming. Dit is een debugbuild voor eigen gebruik, minimaal Android 8.

Voor een nieuwe APK wijzig je alleen version en buildNumber in app_config.json. Huidig: 0.3.3 en 107. Volgende bijvoorbeeld: 0.3.4 en 108. build.gradle leest dit automatisch en controleert de invoer. Het Android-appicoon gebruikt hetzelfde blauwe bruglogo als de app, inclusief ronde en thematische launchericonen.

Meldingen kun je onder de statuskaart inschakelen en uitschakelen; de knop blijft zichtbaar wanneer meldingen actief zijn.
