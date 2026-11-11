# Model Dosyaları

Bu klasöre aşağıdaki 3 ONNX dosyasını koymanız gerekiyor:

1. **melspectrogram.onnx**  
   openWakeWord özellik çıkarma modeli

2. **embedding_model.onnx**  
   openWakeWord konuşma embedding modeli

3. **uyan.onnx**  
   Senin eğittiğin "uyan" wake word modeli

## Nasıl alınır?

```bash
pip install openwakeword
python -c "from openwakeword import utils; utils.download_models()"
```

Dosyalar genellikle şu konumlarda olur:
- Linux: `~/.cache/openwakeword/` veya site-packages
- Windows: `%USERPROFILE%\.cache\openwakeword\`

`uyan.onnx` dosyasını model eğitiminden sonra buraya kopyalayın.
