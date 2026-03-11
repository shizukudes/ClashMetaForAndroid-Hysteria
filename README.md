# ClashMetaForAndroid (Hysteria Edition)

This is a modified version of ClashMetaForAndroid with built-in Hysteria support using `libuz` and `libload`.

## Features
- **Integrated Hysteria Core**: Runs `libuz` and `libload` as background services.
- **Auto-Config Generation**: Automatically generates a Clash-compatible `config.yaml` that links to the Hysteria local SOCKS5 proxy.
- **Custom UI**: Dedicated settings page for Hysteria account and performance tuning.
- **Pre-configured Defaults**: Default values synced with `ZIVPN` for immediate use.

## How to use
1. Go to **Settings** > **Hysteria Settings**.
2. Enter your ZIVPN ACCOUNT
3. Click **Generate & Activate Profile**.
4. Return to the main screen and click the start button.

## Credits
- Based on [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)
- Core binaries from `ZIVPN`

## Perbedaan singkat vs Clash Meta Alpha Official
- **Fokus Hysteria/ZIVPN**: fork ini menambah alur konfigurasi Hysteria langsung di aplikasi (akun, template, generate config).
- **Binary tambahan**: membawa `libuz` + `libload` untuk mode Hysteria/load-balance lokal.
- **Official Alpha** lebih general-purpose dan mengikuti upstream behavior tanpa penyesuaian khusus Hysteria pada UI/alur profile seperti di fork ini.
-
## Donate
- Traktir orang gabut coffee ☕: https://saweria.co/Damnwhoknows
