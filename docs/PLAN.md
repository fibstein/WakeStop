# WakeStop — Android Uygulama Planı

## Context

Otobüs/serviste uyuyup durağı kaçırma problemi. WakeStop, kullanıcı seçtiği varış noktasına yaklaştığında (mesafe veya tahmini süre bazlı) kilit ekranında bile çalan sesli + titreşimli bir alarmla uyandırır.

Repo: `/home/user/wakestop` (`fibstein/WakeStop`) — yalnızca `README.md` var, proje sıfırdan kurulacak.

Alınan kararlar: **Kotlin + Jetpack Compose**, **MapLibre + OpenStreetMap verisi** (API key yok), kapsam **çekirdek + favoriler + akıllı özellikler**.

## Temel teknik kararlar

| Konu | Karar | Gerekçe |
|---|---|---|
| Konum takibi | `FusedLocationProviderClient` + **foreground service** (`foregroundServiceType="location"`) | Geofencing API arka planda dakikalarca gecikebiliyor ve hız/ETA vermiyor; uyuyan biri için kabul edilemez. Aktif takip şart. |
| Konum izni | Yalnızca `ACCESS_FINE_LOCATION` ("uygulamayı kullanırken") | Servis uygulama ön plandayken başlatıldığı için `ACCESS_BACKGROUND_LOCATION` gerekmez; Play Store incelemesi de kolaylaşır. |
| Pil | Uyarlanabilir örnekleme: uzakken ~30–60 sn / balanced, yaklaşınca 2–5 sn / high accuracy | Uzun yolculukta pili bitirmeden hedef yakınında hassasiyet. |
| Alarm | Full-screen intent bildirimi + kilit ekranı üstünde açılan `AlarmActivity`; ses çıkışı kulaklık/sessiz mod durumuna göre seçilir (bkz. "Alarm ses yönlendirme") | Otobüste çevreyi rahatsız etmeden uyandırmak; sessiz mod davranışı kullanıcı tercihi. |
| Harita | MapLibre Android SDK (`AndroidView` içinde `MapView`), stil: OpenFreeMap | OSM'in kendi tile sunucusu uygulama kullanımına izin vermiyor; OpenFreeMap ücretsiz ve key'siz. |
| Yer arama | Photon (komoot) geocoding API, debounce'lu | Ücretsiz, key'siz; fair-use limitli olduğu için istekler seyrek tutulacak. |
| Kalıcı veri | Room (favoriler), DataStore (ayarlar + aktif yolculuk durumu) | Servis sistem tarafından öldürülürse `START_STICKY` ile kaldığı yerden devam edebilmeli. |
| DI | Manuel `AppContainer` | Bu boyutta Hilt gereksiz build karmaşıklığı. |
| SDK | `minSdk 26`, `compileSdk/targetSdk` güncel stable | Kütüphane sürümleri kurulum anında güncel stable'a sabitlenecek (`libs.versions.toml`). |

## Mimari

Tek modül (`app`), paket kökü `com.fibstein.wakestop`:

```
ui/
  home/        HomeScreen — harita, nokta seçimi, arama, yarıçap slider, "Başlat"
  favorites/   FavoritesScreen — kayıtlı duraklar, tek dokunuşla başlat
  tracking/    TrackingScreen — kalan mesafe, ETA, iptal
  alarm/       AlarmActivity — tam ekran, kaydırarak kapat
  settings/    SettingsScreen — varsayılan yarıçap, süre eşiği, ses, titreşim
  onboarding/  PermissionsScreen — izinler + pil optimizasyonu yönlendirmesi
tracking/
  TripEngine.kt            saf Kotlin karar mantığı (Android bağımlılığı yok)
  LocationTrackingService  foreground service; konum akışını TripEngine'e besler
  TripRepository           aktif yolculuk durumu (StateFlow + DataStore)
alarm/
  AlarmRouter              kulaklık + sessiz mod + ayara göre çıkış kararı (saf mantık, test edilebilir)
  AlarmPlayer              MediaPlayer + Vibrator, kademeli ses artışı, cihaz yönlendirme
  AlarmNotifier            bildirim kanalları, full-screen intent
data/
  db/         FavoriteStop entity, DAO, AppDatabase
  prefs/      SettingsStore
  geocoding/  PhotonClient
```

### TripEngine (uygulamanın çekirdeği)

Girdi: konum fix'leri (lat, lon, accuracy, speed, timestamp) + periyodik tick. Çıktı: `PreAlert`, `Alarm`, `SignalLost`, `SignalRestored` event'leri.

- **Mesafe tetikleyici:** `mesafe − accuracy ≤ yarıçap` → `Alarm`. Düşük doğruluklu tek bir fix'e güvenmemek için accuracy eşiği uygulanır.
- **Süre tetikleyici:** hız EMA ile yumuşatılır; `ETA = mesafe / hız ≤ eşik (örn. 3 dk)` ve hız anlamlı bir alt sınırın üstündeyse → `Alarm`. Durakta/ışıkta beklerken ETA sonsuza gittiği için mesafe tetikleyici her zaman paralel çalışır.
- **Kademeli alarm:** eşiğin ~2 katında `PreAlert` (kısa titreşim + bildirim), eşikte tam alarm; alarm sesi 30 sn içinde kademeli yükselir.
- **GPS kaybı:** N sn (örn. 60) fix gelmezse `SignalLost` → uyarı bildirimi + titreşim. Son bilinen mesafe/hıza göre tahmini varış süresi dolarsa **fail-safe olarak alarm çalar** (tünel/metro senaryosu).
- Alarm bir yolculukta bir kez tetiklenir; kapatılınca servis durur.

Bilinen sınır: mesafe kuş uçuşu hesaplanır (rota değil). Kıvrımlı güzergâhta süre tahmini iyimser olabilir; yarıçap tetikleyici bunu telafi eder. Rota bazlı ETA bu sürümde yok.

### Alarm ses yönlendirme

Alarm tetiklendiği anda `AlarmRouter` çıkışı belirler; titreşim **her durumda** çalışır.

| Durum | Davranış |
|---|---|
| Kulaklık bağlı (kablolu / Bluetooth / USB) | Ses **yalnızca kulaklıktan**; telefonun sessiz modu dikkate alınmaz (kimseyi rahatsız etmez) |
| Kulaklık yok, telefon normal modda | Hoparlörden sesli alarm |
| Kulaklık yok, telefon sessiz/titreşim modunda | Kullanıcı ayarı: **"Sadece titreşim"** veya **"Yine de sesli çal"** |

Teknik notlar:
- Kulaklık tespiti: `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` → `WIRED_HEADSET`, `WIRED_HEADPHONES`, `USB_HEADSET`, `BLUETOOTH_A2DP`, `BLE_HEADSET`.
- Android'de `USAGE_ALARM` tasarım gereği kulaklık takılıyken **hoparlörden de** çalar. Yalnızca kulaklıktan çalmak için bu durumda `USAGE_MEDIA` + `setPreferredDevice(kulaklık)` kullanılır; audio focus alınarak çalan müzik/video durdurulur.
- Medya sesi 0 veya çok kısıksa alarm duyulmaz: alarm süresince medya sesi asgari bir seviyeye çıkarılır, alarm kapanınca eski değerine döndürülür.
- Hoparlör yolunda `USAGE_ALARM` kullanılır (alarm ses seviyesi; sessiz modu aşar). Sessiz mod tespiti: `AudioManager.ringerMode != RINGER_MODE_NORMAL`.
- Alarm çalarken kulaklık koparsa (`AudioDeviceCallback`) "kulaklık yok" kurallarına geçilir.
- **Fail-safe (ayar, varsayılan açık):** Kulaklık kulaktan düşmüş olabilir. Alarm N sn (varsayılan 30) içinde kapatılmazsa hoparlöre de geçer. Sessiz modda "sadece titreşim" seçiliyse bu geçiş yapılmaz.
- Sınır: Bluetooth cihazının kulaklık mı araç/hoparlör mü olduğu güvenilir biçimde ayırt edilemez; A2DP cihazı kulaklık kabul edilir.
- Ayarlar ekranında "sadece titreşim" seçeneğinin yanında, titreşimin derin uykuda uyandırmayabileceğine dair uyarı gösterilir.

Ayarlar (`SettingsStore`): `silentModeBehavior` (VIBRATE_ONLY | SOUND), `headphoneFallbackToSpeaker` (bool), `headphoneFallbackDelaySec`.

### İzinler (manifest)

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

- Android 14+: `canUseFullScreenIntent()` false ise kullanıcı ilgili ayar ekranına yönlendirilir.
- Xiaomi/Huawei/Samsung gibi agresif pil yöneticilerinde servis öldürülebilir; onboarding'de pil optimizasyonu muafiyeti istenir. Bu, uygulamanın en büyük güvenilirlik riski ve yazılımla tamamen çözülemez.

## Uygulama adımları

1. **İskelet:** Gradle (Kotlin DSL, version catalog), Compose + Material 3, navigation, manifest, `.gitignore`. Android SDK cmdline-tools kurulumu; `assembleDebug` yeşil.
2. **Harita + nokta seçimi:** MapLibre, mevcut konum, uzun basarak/merkez pin ile varış seçimi, yarıçap dairesi çizimi, Photon ile arama.
3. **TripEngine + unit testler:** mesafe/ETA/GPS kaybı senaryoları sentetik fix dizileriyle.
4. **LocationTrackingService:** foreground bildirim (kalan mesafe canlı), uyarlanabilir örnekleme, durum kalıcılığı, `TrackingScreen`.
5. **Alarm:** kanallar, full-screen intent, `AlarmActivity` (kilit ekranı üstü, ekranı açar, kaydırarak kapat), `AlarmRouter` + `AlarmPlayer` (kulaklık yönlendirme, sessiz mod tercihi, hoparlöre fail-safe).
6. **Favoriler + ayarlar:** Room, DataStore, ilgili ekranlar.
7. **Onboarding + sağlamlaştırma:** izin akışı, pil optimizasyonu, konum servisi kapalı / izin reddi durumları.
8. **CI:** GitHub Actions ile debug APK build + artifact (telefona kurulabilir APK'yı buradan indirmek için).

Geliştirme `main` yerine ayrı bir branch'te yapılır.

## Doğrulama

- `./gradlew testDebugUnitTest` — TripEngine senaryoları: yarıçapa giriş, ETA tetikleme, durakta bekleme (yanlış alarm yok), düşük doğruluklu fix, sinyal kaybı + fail-safe.
- `./gradlew assembleDebug lintDebug` — derleme ve lint.
- Bu cloud ortamında emülatör çalıştırılamayabilir (KVM yok); UI ve alarm davranışı CI'dan indirilen APK ile **gerçek cihazda** doğrulanmalı:
  - Mock location / GPX rotası ile simüle yolculuk → pre-alert ve alarm doğru noktada.
  - Alarm matrisi (ekran kilitliyken): kablolu ve Bluetooth kulaklıkla ses yalnızca kulaklıktan mı; kulaklıksız sessiz modda iki ayar seçeneği; medya sesi 0 iken kulaklıkta duyuluyor mu; alarm sırasında kulaklığı çıkarma; 30 sn sonra hoparlöre geçiş.
  - `AlarmRouter` karar tablosu ayrıca unit test ile doğrulanır.
  - Uçak modu ile GPS kaybı uyarısı ve fail-safe alarm.
  - Gerçek bir otobüs yolculuğunda uçtan uca test, pil tüketimi gözlemi.

Ortam riski: SDK indirmesi için `dl.google.com` ve `maven.google.com` erişimi gerekir; network policy engellerse adım 1'de ortam ayarı güncellenmesi istenecek.
