# USDB Mobil

Android-App zum Suchen und Herunterladen von UltraStar-Songs von usdb.animux.de.
Der Ablauf pro Song entspricht dem USDB Syncer (GPL-3.0): Songtext holen, Audio/Video
per yt-dlp laden (mit Ersatzquellen aus den Kommentaren), Cover und Hintergrund über die
Meta-Tags `co=`/`bg=` (inkl. rotate/crop/resize/contrast und maximaler Größe) laden,
Header in der .txt anpassen und alles samt `<id>.usdb` im Zielordner ablegen.

## Bedienung
1. **Einstellungen**: USDB-Konto eintragen („Anmelden & testen“) und Zielordner wählen.
   Dort auch Audio-, Video-, Cover- und Hintergrund-Optionen wie im Syncer.
2. **Suche**: Interpret oder Titel eingeben („Interpret - Titel“ filtert beides zugleich).
   Filter: Sortierung (Datum neu/alt, Interpret A–Z/Z–A, Titel A–Z/Z–A), Sprache
   (Alle / Systemsprache / Englisch / Andere) und „Geladene Songs“.
3. Auf **Laden** tippen. Fortschritt im Tab „Downloads“, auch bei geschlossener App.
4. Schon geladene Songs zeigen ein Häkchen („Geladen“). Antippen → „Neu laden“.

## Erkennung geladener Songs
Wie beim Syncer legt die App pro Song-Ordner eine `<11 Zeichen>.usdb`-Datei an (JSON, Version 1).
Beim Start und beim Wechsel des Zielordners werden alle Unterordner nach solchen Dateien
durchsucht – so werden auch vom Desktop-Syncer geladene Songs erkannt. ZIP-Downloads
merkt sich die App in einer eigenen Liste (in eine ZIP kann sie nicht hineinsehen).

## APK bauen
**Variante A – Android Studio:** Ordner öffnen, Sync abwarten (erzeugt den Gradle-Wrapper),
dann *Build > Build APK(s)*.

**Variante B – ohne Android Studio:** Projekt in ein GitHub-Repository hochladen. Der Workflow
`.github/workflows/build.yml` baut die Debug-APK; sie liegt dann unter *Actions > Artifacts*.

## Bekannte Annahmen / Stolpersteine
- Feldnamen der USDB-Suche (`interpret`, `title`, `language`) und Sortierwerte
  (`id`, `interpret`, `title`) sind angenommen (`UsdbClient.search`, `Options.kt › Sort`).
- „Cover/Audio einbetten“ (Embed artwork) ist nicht umgesetzt.
- YouTube ändert sich oft; bei Fehlern in den Einstellungen „yt-dlp aktualisieren“ tippen.
