# BT Logger

Android aplikace, která zaznamenává, co se děje s Bluetooth zvukem, a umožňuje dvojitým stiskem tlačítka hlasitosti nahoru označit okamžik výpadku.

## Sestavení

1. Nahrajte obsah této složky do nového GitHub repozitáře (např. `bt-logger`).
2. GitHub Actions automaticky spustí workflow **Build APK** (záložka *Actions*).
3. Po dokončení stáhněte artefakt **bt-logger-apk** (zip s `app-debug.apk`) a nainstalujte do telefonu.

## První spuštění

1. **Povolit oprávnění** – „Zařízení v okolí“ (Bluetooth) a notifikace.
2. **Zapnout značky tlačítkem hlasitosti** – Nastavení → Usnadnění → Nainstalované aplikace → BT Logger → zapnout.
   Pokud je přepínač šedý („Omezené nastavení“): Nastavení → Aplikace → BT Logger → ⋮ → *Povolit omezená nastavení*.
3. **Vypnout optimalizaci baterie** – aby Android záznam na pozadí neukončil.
4. **Spustit záznam**, připojit telefon k autu a pustit YouTube.

## Během jízdy

- Když vypadne zvuk: **2× rychle hlasitost +** (telefon dvakrát krátce zavibruje).
  Hlasitost se po značce sama vrátí zpět.
- Záložní možnost: tlačítko **Značka** v notifikaci.

## Co se zaznamenává

| Typ | Význam |
|---|---|
| `ZNACKA` | vaše značka výpadku |
| `A2DP_STOP` / `A2DP_START` | Bluetooth přestal / začal posílat zvuk (s délkou přerušení) |
| `HUDBA_STOP` / `HUDBA_START` | systém hlásí, že hudba nehraje / znovu hraje |
| `PREHRAVAC` | které přehrávače jsou aktivní (média, notifikace, navigace…) a kam hrají |
| `A2DP_SPOJENI`, `HFP_*`, `BT_ACL`, `SCO` | spojení s autem, hovorový kanál |
| `KODEK` | změna Bluetooth kodeku |
| `AUDIO_REZIM` | přepnutí do hovoru / komunikace (např. asistent) |
| `VYSTUP` | přidání / odebrání zvukového výstupu |
| `DISPLEJ`, `NABIJENI`, `USPORA`, `WIFI`, `HLASITOST` | okolní události |

**Rozbor** ukáže ke každé značce události 8 s před ní a 2 s po ní.
**Sdílet log** pošle CSV soubor i s rozborem.

## Omezení

- Samotný krátký výpadek Android aplikacím přímo nehlásí – aplikace zachytí jen to, co se kolem něj stalo.
- Při zhasnutém displeji některé telefony stisk hlasitosti službám usnadnění nepředávají. Ověřte si to doma; jinak nechte displej zapnutý nebo použijte značku v notifikaci.
