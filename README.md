# v2rayNG

**Fork:** [klockky/v2rayNG](https://github.com/klockky/v2rayNG) · upstream: [2dust/v2rayNG](https://github.com/2dust/v2rayNG)

A V2Ray client for Android, support [Xray core](https://github.com/XTLS/Xray-core) and [v2fly core](https://github.com/v2fly/v2ray-core)

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/klockky/v2rayNG)](https://github.com/klockky/v2rayNG/commits/master)
[![GitHub Releases](https://img.shields.io/github/downloads/klockky/v2rayNG/latest/total?logo=github)](https://github.com/klockky/v2rayNG/releases)

### Changes in this fork

| Area | What was done |
|------|----------------|
| **Local SOCKS / loopback** | Per-session login and password on the local SOCKS inbound (and matching credentials in **hev-socks5-tunnel**). Stops other apps on the device from using your local proxy without credentials ([context](https://habr.com/ru/articles/1020080/)). System **HTTP proxy hint** via `VpnService` is **not** set: Android cannot pass proxy auth, and an open HTTP proxy on `127.0.0.1` would be a bypass. Normal use is still **tap connect** — traffic goes through TUN; you do not type proxy passwords in the browser. |
| **HWID / subscription requests** | **[v2rayNG-DeviceKit-Addon](https://github.com/klockky/v2rayNG-DeviceKit-Addon)** (`V2rayNG/devicekit`): configurable device / HWID-style headers for subscription fetches (`HttpUtil` → `Kit.applyToConnectionFromSettings`). Helps avoid tying subscription pulls to a single hardware fingerprint when the server expects Happ-like identifiers. |
| **Happ subscription links** | Encrypted **`happ://`** subscription URLs are decrypted via **HappDecryptor** (including **`happ://crypto1`** … **`happ://crypto4`** style links) when adding or updating subscriptions. |

### Для русскоязычных пользователей

В этом форке: закрыт вектор с **беспарольным локальным SOCKS** на `127.0.0.1` (новые логин/пароль на каждый старт VPN, те же данные уходят в hev и в ядро). Добавлены **подмена/настройка HWID и связанных заголовков** для запросов подписок (модуль DeviceKit) и **расшифровка подписок по ссылкам `happ://`** (включая варианты **crypto1–crypto4**).

### Telegram (upstream)

[github_2dust](https://t.me/github_2dust)

### Usage

#### Geoip and Geosite
- geoip.dat and geosite.dat files are in `Android/data/com.v2ray.ang/files/assets` (path may differ on some Android device)
- download feature will get enhanced version in this [repo](https://github.com/Loyalsoldier/v2ray-rules-dat) (Note it need a working proxy)
- latest official [domain list](https://github.com/Loyalsoldier/v2ray-rules-dat) and [ip list](https://github.com/Loyalsoldier/geoip) can be imported manually
- possible to use third party dat file in the same folder, like [h2y](https://guide.v2fly.org/routing/sitedata.html#%E5%A4%96%E7%BD%AE%E7%9A%84%E5%9F%9F%E5%90%8D%E6%96%87%E4%BB%B6)

### More in upstream [wiki](https://github.com/2dust/v2rayNG/wiki)

### Development guide

Android project under V2rayNG folder can be compiled directly in Android Studio, or using Gradle wrapper. But the v2ray core inside the aar is (probably) outdated.  
The aar can be compiled from the Golang project [AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite) or [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).
For a quick start, read guide for [Go Mobile](https://github.com/golang/go/wiki/Mobile) and [Makefiles for Go Developers](https://tutorialedge.net/golang/makefiles-for-go-developers/)

This fork may include the **DeviceKit** submodule / local module; clone with submodules if your build expects it.

v2rayNG can run on Android Emulators. For WSA, VPN permission need to be granted via
`appops set [package name] ACTIVATE_VPN allow`
