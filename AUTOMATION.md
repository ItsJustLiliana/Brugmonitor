# Alleen de server automatisch bijwerken

De workflow **Deploy Brugmonitor server** draait bij een serverwijziging op main. Eerst worden de tests uitgevoerd, daarna maakt GitHub via Tailscale verbinding met dezelfde Arch-server als StickStat. De server haalt main op, installeert dependencies, draait de tests en herstart brugmonitor.service en controleert of de diagnose-API bereikbaar is. Een mislukte update wordt niet als voltooid gemarkeerd. De APK bouw je zelf met build-release.cmd.

## Eenmalig instellen

1. Open https://github.com/ItsJustLiliana/Brugmonitor/settings/secrets/actions.
2. Voeg **TS_OAUTH_CLIENT_ID** en **TS_OAUTH_SECRET** toe: dezelfde Tailscale OAuth-client als voor StickStat. GitHub kan secrets van een andere repository niet automatisch overnemen. Heb je organisatie-secrets, geef Brugmonitor toegang.
3. De bestaande Tailscale-regels moeten tag:github-actions laten verbinden met marijn op archlinux.tail50bfa9.ts.net. Dit is dezelfde verbinding als StickStat.
4. Op de server moet /projects/Brugmonitor een schone Git-checkout op main zijn, met de bestaande .env, .venv en werkende brugmonitor.service.
5. Open https://github.com/ItsJustLiliana/Brugmonitor/actions en start **Deploy Brugmonitor server** met **Run workflow** op main. Daarna starten serverwijzigingen de workflow vanzelf.

Bij alleen Android- of documentatiewijzigingen blijft de server draaien. Lokale wijzigingen aan bijgehouden serverbestanden blokkeren automatisch bijwerken; .env en andere genegeerde instellingen blijven bewaard. Er is geen domein of inkomende poort nodig en de Firebase-serverkey blijft op de server.

Controleer op de server met `systemctl --user status brugmonitor.service` en `journalctl --user -u brugmonitor.service -n 50 --no-pager`.

## Appversie en publiceren via je website

Pas alleen **version** in **app_config.json** aan, bijvoorbeeld **0.3.4**. Het buildnummer loopt automatisch op tijdens de Android-build; je hoeft dit niet meer in te vullen. Bewaar dezelfde ondertekeningssleutel op je computer.

1. Bouw met **build-release.cmd**. De APK staat in **dist/Brugmonitor-release.apk**.
2. Publiceer het bestand **api/brugmonitor-release.php** uit **C:/wamp64/www/website** naar dezelfde api-map op je live website. Een kopie staat ook onder **deploy/website** in deze repository. Dit is eenmalig nodig; de live API bestond nog niet tijdens het instellen.
3. Open https://liliananuzohra.com/edit-website, kies **Projects**, zoek **Brugmonitor**, en klik **Add Version**.
4. Vul de versie in, upload de APK, voeg eventueel release notes toe en klik **Save Version**. Laat Coming soon en Archive uitgeschakeld.
5. Controleer https://liliananuzohra.com/api/brugmonitor-release.php: de response moet data bevatten met version, buildNumber, packageName nl.brugmonitor.app, sha256 en downloadUrl. Zonder gepubliceerde versie is data null.

De uploadfunctie van de website leest APK-versie, buildnummer, package en hash al automatisch. De nieuwe API sluit op die bestaande gegevens aan, net als die van StickStat.

De app controleert bij openen/terugkeren op updates (maximaal eenmaal per vijf minuten). **Details → Controleren op updates** controleert direct. Bij een nieuwe versie verschijnt een prompt en een knop boven Details. Downloaden gebeurt in de app met controles op grootte, SHA-256, package, versie en dezelfde ondertekening. Android vraagt zo nodig eenmalig toestemming om vanuit Brugmonitor te installeren; daarna bevestig je de installatie in het Android-venster.

De APK die deze updater bevat moet je eerst zelf installeren. Daarna kunnen volgende versies via de app worden gedownload en geinstalleerd.
