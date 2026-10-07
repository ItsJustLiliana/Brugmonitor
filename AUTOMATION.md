# Automatisch bouwen

Iedere push naar main start **Build Brugmonitor**: eerst de servertests en het serverpakket, daarna de Android-build met lint. Pull requests worden ook getest en gebouwd, zonder secrets of downloadbare APK. De handmatige knop **Run workflow** werkt alleen voor main om APK-versienummers in volgorde te houden.

## Eenmalig GitHub instellen

Open https://github.com/ItsJustLiliana/Brugmonitor/settings/secrets/actions en kies **New repository secret**. Doe dit op de Windows-computer waarop de huidige APK is gebouwd.

1. Naam: **GOOGLE_SERVICES_JSON**. Voer in PowerShell vanuit de projectmap uit:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts/copy-actions-secret.ps1 -Name GOOGLE_SERVICES_JSON
   ```
   Plak het klembord in het veld Secret en sla op.
2. Naam: **ANDROID_DEBUG_KEYSTORE_BASE64**. Voer uit:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts/copy-actions-secret.ps1 -Name ANDROID_DEBUG_KEYSTORE_BASE64
   ```
   Plak het klembord in het veld Secret en sla op. Deze sleutel moet dezelfde blijven: anders kan de APK niet over de huidige installatie worden geinstalleerd.
3. Wis je klembord met `Set-Clipboard -Value ''`.
4. Open https://github.com/ItsJustLiliana/Brugmonitor/actions, kies **Build Brugmonitor**, klik **Run workflow** en selecteer **main**.

De Firebase-service-account van de server hoeft niet naar GitHub. De workflow stopt als de benodigde appconfiguratie of ondertekeningssleutel ontbreekt.

## APK downloaden

Open de geslaagde workflowrun en download onder **Artifacts** het pakket **Brugmonitor-APK**. Pak de ZIP uit en installeer **app-debug.apk** op je telefoon. Je moet hiervoor ingelogd zijn op GitHub. Het serverpakket staat onder **Brugmonitor-server**. Downloads blijven 30 dagen beschikbaar.

Dit blijft een debug-APK voor eigen gebruik. De CI-builds krijgen oplopende versionCodes vanaf 101; lokale standaardbuilds hebben code 3 en kunnen daarna niet meer over de CI-app worden geinstalleerd. Gebruik voortaan de APK van de workflow, of stel lokaal BRUGMONITOR_VERSION_CODE in op een hoger runnummer voordat je bouwt. Bewaar dezelfde debug.keystore ook voor toekomstige lokale builds.

Android installeert updates niet automatisch: de workflow maakt de APK beschikbaar, waarna je hem zelf installeert.

GitHub-documentatie: [Repository secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) en [Artifacts downloaden](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts).
