# WakeStop

Otobüste/serviste uyuyakalıp durağı kaçırmamak için Android uygulaması. Varış noktasına yaklaşınca
(mesafe veya tahmini süre bazlı) kilit ekranında bile çalan sesli + titreşimli alarm verir.

Tasarım ve kararlar: [`docs/PLAN.md`](docs/PLAN.md).

## Özellikler

- MapLibre + OpenFreeMap harita (API key yok), uzun basarak veya Photon araması ile varış seçimi, yarıçap dairesi
- Foreground service ile aktif konum takibi, uyarlanabilir örnekleme (uzakta 30 sn / balanced, yakında 3 sn / high accuracy)
- Mesafe tetikleyici + ETA tetikleyici, eşiğin 2 katında ön uyarı
- GPS kaybında uyarı; son hız/mesafeye göre tahmini varışta fail-safe alarm
- Tam ekran alarm (kilit ekranı üstü, kaydırarak kapat), 30 sn'de kademeli yükselen ses
- Kulaklık bağlıysa ses yalnızca kulaklıktan; sessiz modda "sadece titreşim" / "yine de sesli" tercihi; kapatılmazsa hoparlöre geçiş
- Favori duraklar (Room), ayarlar (DataStore), izin/pil optimizasyonu onboarding'i

## Proje yapısı

```
app/src/main/java/com/fibstein/wakestop/
  tracking/   TripEngine (saf karar mantığı), SamplingPolicy, LocationTrackingService, TripRepository
  alarm/      AlarmRouter (saf yönlendirme tablosu), AlarmPlayer, AlarmNotifier, Haptics
  data/       Room (favoriler), SettingsStore (DataStore), PhotonClient
  ui/         home, tracking, alarm, favorites, settings, onboarding
```

## Derleme

JDK 17+ ve Android SDK (platform `android-37.0`) gerekir.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions her push'ta aynı adımları çalıştırır ve APK'yı
`wakestop-debug-apk` artifact'ı olarak yükler.

> CI'daki debug APK'lar her çalıştırmada farklı bir debug anahtarıyla imzalanır; yeni bir build'i kurmadan önce
> eskisini kaldırmak gerekebilir.

## Bilinen sınırlar

- Mesafe kuş uçuşu hesaplanır, rota bazlı değildir; kıvrımlı güzergâhta ETA iyimser olabilir.
- Bluetooth A2DP cihazları (araç kiti, hoparlör) kulaklık sayılır.
- Agresif üretici pil yöneticileri (Xiaomi, Huawei, Samsung…) servisi öldürebilir. Sistem servisi yeniden başlatsa da
  Android 12+ arka plandan foreground service başlatmayı kısıtladığı için takip ancak uygulama açılınca devam edebilir.
- UI, alarm ve konum davranışı gerçek cihazda doğrulanmalıdır (bkz. `docs/PLAN.md` → Doğrulama).
