# Hotspot Tile (Türkçe)

[English](README.md)

Pixel için Hızlı Ayarlar karosu + panel: hotspot'u aç/kapat, ağ ayarlarını değiştir, QR ile paylaş, bağlanan cihazları yönet.
Android 17 Pixel'de geliştirildi (Android 13+ hedeflenir). Başlatıcı simgesi yoktur; yalnızca karo ve panel. Paket: `com.mbaha.hotspottile`.

**Özellikler:** karo (kısa dokunuş aç/kapat, uzun basış panel) · QR ile paylaşım (parmak izi korumalı) · ad, şifre, güvenlik (WPA2/WPA3),
bant ve kanal, cihaz sınırı, Wi-Fi 6/7, MAC gizliliği · bağlı cihazları düşür/engelle, isim ver, güvenilir yap · yeni cihaz bildirimi ·
"yalnızca güvenilen cihazlar" · boştayken / zamanlayıcıyla / her gün belirli saatte kapat · TR/EN çeviri.

## Hazır APK ile kurulum (derlemeden)

[Releases](../../releases) sayfasından `hotspottile-<sürüm>.apk` dosyasını indirin. Uygulama hotspot'u yönetebilmek için telefonda
`adb` yetkisine ihtiyaç duyar; bu yüzden **bir kez** bilgisayara ihtiyacınız var.

1. Telefonda **Geliştirici seçenekleri → USB hata ayıklama**'yı açın, USB ile bilgisayara bağlayın ve bilgisayara izin verin.
2. Bilgisayarda (`adb` yüklü olmalı):
   ```bash
   adb install -g hotspottile-0.1.0.apk
   adb shell pm grant com.mbaha.hotspottile android.permission.WRITE_SECURE_SETTINGS
   adb tcpip 5555
   adb shell am start -n com.mbaha.hotspottile/.PanelActivity
   ```
3. Telefonda çıkan pencereler:
   - **"USB hata ayıklamaya izin ver?"** (`hotspot@tile`) → **"Her zaman izin ver"** işaretleyip onaylayın.
   - **"Hızlı Ayarlar'a karo ekle"** → karoyu ekleyin (çıkmazsa Hızlı Ayarlar → düzenle → Hotspot).
   - Bildirim izni → yeni cihaz uyarıları için izin verin.
4. Hazır. **Kısa dokunuş** hotspot'u açar/kapatır, **uzun basış** paneli açar. Başlatıcıda simge yoktur; panele karo üzerinden ulaşırsınız.

### Sonrasında yapmanız gerekenler
- **Yeniden başlatma:** `adb tcpip 5555` komutunu **bir daha yazmanız gerekmez**. Telefon herhangi bir Wi-Fi ağına bağlanınca uygulama
  Kablosuz hata ayıklamayı geçici açıp 5555'i kendisi yeniden açar (Panel → Gelişmiş'ten kapatılabilir).
  - Yeni bir Wi-Fi ağında Android bir kez "Bu ağda kablosuz hata ayıklamaya izin ver?" sorar → **"Bu ağda her zaman izin ver"**.
  - Telefon hiç Wi-Fi'ye bağlanmadıysa (yalnız mobil veri) otomatik açma çalışmaz; o durumda bilgisayardan `adb tcpip 5555` yeterli.
- **Güncelleme:** yeni APK'yı `adb install -r` ile üstüne kurun (aynı imza anahtarıyla imzalı olduğu sürece ayarlar ve yetkiler korunur).
- **Kaldırma:** `adb uninstall com.mbaha.hotspottile`. Telefonda `/data/local/tmp/hotspottile.*` dosyaları ve yardımcı süreç yeniden başlatmada kendiliğinden gider.

## Kendiniz derleyin
Gereksinimler (Mac): Android SDK (`~/Library/Android/sdk`) içinde **build-tools 36.1.0** ve **platforms/android-36**, JDK 11+, `adb`.
```bash
./build.sh                  # -> build/app.apk   (VERSION_NAME=0.1.1 VERSION_CODE=2 ./build.sh ile sürüm verilebilir)
./build.sh install          # derle + kur + WRITE_SECURE_SETTINGS izni (telefon USB'de, hata ayıklama açık)
adb tcpip 5555              # bir kez; sonrası otomatik (yukarıya bakın)
```
Gradle yok. İmza anahtarı `keystore/local.jks` yoksa betik üretir (repoya girmez). `DEBUG_MODE=1 ./build.sh` debuggable derler.

## Notlar
- Eski paketten (`dev.hotspot.tile`) geliyorsanız ayrı bir uygulamadır: `adb uninstall dev.hotspot.tile`, yeni uygulama kendi ADB anahtarını üretir.
- 6 GHz hotspot ülke kısıtına bağlıdır; sistem o ülkede kanal bildirmiyorsa (ör. TR) düğme gizlenir, istek sessizce 5 GHz'e düşer.
- Yeni cihaz bildirimi ve "yalnızca güvenilen cihazlar" için telefonda küçük bir yardımcı süreç çalışır; kalıcı bildirim yoktur.
- Güvenlik: 5555 portu açıkken Wi-Fi'deki herkese dinler, ama bağlanmak için telefonda onaylanmış ADB anahtarı gerekir.
