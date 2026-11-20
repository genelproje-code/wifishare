# WiFi Share

An Android app that shares the phone's **Wi-Fi internet** with other devices (phones, laptops,
tablets, TVs) through a password-protected hotspot. The phone stays connected to its Wi-Fi the
whole time. You don't need root.

## How it works

1. Tap **Start**. The phone creates its own Wi-Fi network (`DIRECT-WS-…`, protected with WPA2).
2. The other device joins the network by **scanning the QR code** or typing the password.
3. On the other device, set the Wi-Fi **proxy** to the address the app shows
   (Manual: `192.168.49.1`, port `8282`, or Automatic with the `proxy.pac` URL).
4. Done. Web browsing, apps, streaming and downloads go through this phone's Wi-Fi.

The app shows how many devices are connected and how much data has been shared. Sharing keeps
running in the background with a notification, which has a **Stop** button.

## Get the APK

### Option A: GitHub (no software needed)
1. Create a new repository on github.com and upload the contents of this folder
   (with the hidden `.github` folder included).
2. Open the **Actions** tab. The "Build APK" job runs automatically (about 5 minutes).
3. Open the finished run, then download **WiFiShare-apk** under *Artifacts*. Unzip it to get `app-release.apk`.
4. Copy the APK to the phone and open it (allow "Install unknown apps" when asked).

### Option B: Android Studio
1. Install Android Studio, then **File › Open** this folder.
2. Wait for Gradle sync, connect the phone with USB debugging on, and press **Run ▶**.
   Or use **Build › Build APK(s)**.

## Requirements
- Android **10 or newer**, with Wi-Fi Direct (almost every phone has it).
- The phone must be connected to Wi-Fi. On Android 10–12, Location must also be on (an Android
  rule for Wi-Fi Direct). The app doesn't use your location.

## Good to know
- **Why a proxy?** Android doesn't let normal apps turn on the built-in hotspot while the phone
  is on Wi-Fi, so the app creates a Wi-Fi Direct network instead. Internet is passed through a
  built-in proxy on that network. This is the same method that apps like NetShare and PdaNet use.
- **What works:** anything that respects the system proxy, which covers browsers, YouTube,
  Netflix, social apps, app stores, Windows/macOS updates and most apps.
  **What may not work:** online games and calls that use UDP, VPN apps, and some apps that
  ignore proxy settings.
- **Built-in alternative:** many recent Samsung, Xiaomi, Pixel and OnePlus phones have a built-in
  "Wi-Fi sharing" option ("Share Wi-Fi via hotspot") under Settings › Hotspot. When it's
  available, it needs no proxy setup.
- **Security:** the network uses WPA2 with your password. The proxy only listens on the hotspot
  network, so devices on your normal Wi-Fi can't use it.
- If an older device can't see the network, turn on **2.4 GHz compatibility mode** in Settings.
- Keep the phone charging during long sessions.

## Project layout
| File | Purpose |
|---|---|
| `MainActivity.kt` | The single screen: start/stop, QR code, connection details, settings |
| `HotspotService.kt` | Background service: creates the Wi-Fi Direct network, runs the proxy, keeps Wi-Fi awake |
| `ProxyServer.java` | HTTP/HTTPS proxy (plain Java), forwards traffic over Wi-Fi, serves `proxy.pac` |
| `Settings.kt`, `HotspotState.kt` | Saved settings and shared status |

The release APK is signed with the debug key so it installs directly. Sign it with your own key
before publishing on Google Play.
