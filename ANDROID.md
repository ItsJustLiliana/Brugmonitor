# Android 0.3.3

De app krijgt de brugstatus via Firestore en meldingen via Firebase Cloud Messaging. Volg SERVER-SETUP.md voor de Arch-server en Firebase-configuratie.

1. Plaats google-services.json voor nl.brugmonitor.app in android/app/google-services.json.
2. Bouw de release lokaal met **build-release.cmd**. Voor een debug-APK blijft **build-apk.cmd** beschikbaar.
3. Installeer **dist/Brugmonitor-release.apk**. Bij overstappen van de oude debug-app moet je die eenmalig verwijderen omdat de release een eigen ondertekening heeft. Daarna kunnen releases over elkaar worden geinstalleerd.
4. Schakel meldingen in en geef Android toestemming.

Pas de versie op een plek aan: **app_config.json** (alleen version). Het buildnummer wordt automatisch bepaald tijdens de Android-build. Publiceren op liliananuzohra.com en de automatische serverworkflow staan in **AUTOMATION.md**. De APK bouw je zelf.

De app toont updates boven de Details-dropdown, downloadt de APK en opent de Android-installer na controle. Gebruik dezelfde ondertekeningssleutel voor iedere build.

Meldingen verschijnen niet bovenaan? Open **Details → Meldingen op scherm instellen** en activeer geluid en **Weergeven als pop-up** of **Op scherm tonen** voor Brugstatus. Android bewaart eerdere kanaalinstellingen na een update. Nieuwere brugmeldingen vervangen de vorige, zowel met geopende als gesloten app.

Firebase-afspraken: topic brugmonitor-sas-van-gent-v1, document bridges/sas-van-gent, kanaal bridge_status. De Firebase-serverkey hoort alleen op de server. De app vraagt op Android 13+ meldingstoestemming. De app vereist minimaal Android 8.

Voor een nieuwe appversie wijzig je alleen version in app_config.json, bijvoorbeeld van 0.3.3 naar 0.3.4. Elke build krijgt automatisch een hoger buildnummer op basis van de tijd, ook als de versienaam gelijk blijft. Een lokale teller in android/.gradle voorkomt dubbele nummers bij snel opeenvolgende builds; deze gaat niet naar Git. build.gradle leest dit automatisch en controleert de invoer. Het Android-appicoon gebruikt hetzelfde blauwe bruglogo als de app, inclusief ronde en thematische launchericonen.

Meldingen kun je onder de statuskaart inschakelen en uitschakelen; de knop blijft zichtbaar wanneer meldingen actief zijn.

De eerste release-build maakt automatisch een vaste ondertekeningssleutel. Maak een veilige back-up van **.secrets/brugmonitor-release.jks** en **android/keystore.properties** samen. Beide zijn uitgesloten van Git. Bewaar deze bestanden ook bij verhuizing naar een andere computer: toekomstige updates moeten dezelfde sleutel gebruiken. Publiceer vanaf nu de release-APK op de website.
