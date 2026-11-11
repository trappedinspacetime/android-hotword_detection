# Uyan Wake Word

Android üzerinde sürekli dinleyen, **"uyan"** kelimesini algılayınca ekranı uyandıran minimal uygulama.

SMA Tip 3 gibi motor engeli olan kullanıcılar için erişilebilirlik odaklı tasarlandı.

## Özellikler

- openWakeWord (ONNX) ile tamamen cihaz içi algılama
- Foreground Service (mikrofon tipi) → ekran kapalıyken de çalışır
- Kilit ekranında görünme + ekran uyandırma
- Batarya optimizasyonu muafiyeti isteği
- Boot sonrası yeniden başlatma desteği (Android 10 ve altı)

## Kurulum (GitHub Actions ile)

1. Bu repoyu kendi GitHub hesabına fork et veya yeni repo oluşturup dosyaları yükle.
2. `app/src/main/assets/` klasörüne şu 3 dosyayı koy:
   - `melspectrogram.onnx`
   - `embedding_model.onnx`
   - `uyan.onnx`  ← senin eğittiğin model
3. GitHub Actions sekmesine git → **Build APK** workflow'unu çalıştır (veya push et).
4. Biten işten **uyan-debug-apk** artifact'ini indir.
5. Telefona yükle (bilinmeyen kaynaklardan yüklemeye izin ver).

## Model Dosyaları

openWakeWord standart feature modelleri (`melspectrogram.onnx` + `embedding_model.onnx`) şu şekilde alınabilir:

```bash
pip install openwakeword
python -c "
from openwakeword import utils
utils.download_models()
"
# Dosyalar genellikle ~/.cache veya site-packages altında olur
```

`uyan.onnx` modelini eğitmek için ayrı rehber verilecektir.

## İzinler

Uygulama ilk açıldığında mikrofon + bildirim izni ister.  
Ayrıca **Batarya optimizasyonunu kapat** butonuna basman önerilir (aksi halde Android servisi öldürebilir).

## Threshold Ayarı

`WakeWordService.kt` içinde:

```kotlin
threshold = 0.45f
```

Yanlış alarm çoksa yükselt (0.55–0.70), kaçırıyorsa düşür (0.30–0.40).

## Notlar

- Android 11+ cihazlarda boot sonrası otomatik mikrofon servisi başlatmak kısıtlıdır. Uygulamayı bir kez elle açmanız gerekir.
- Eski telefonlarda (Android 8–10) daha sorunsuz çalışır.
- Pil tüketimi model + cihaza göre değişir; sürekli dinleme olduğu için normalden biraz daha yüksektir.
