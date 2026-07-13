# Guardián Robot - App Android

App Android (Kotlin) que actúa como el "cerebro" de un robot guardián con Arduino.

## ¿Qué hace?
1. Activa la **cámara trasera** del teléfono
2. Procesa frames a **2.5 FPS** con **EfficientDet-Lite0 (COCO)**
3. Si detecta **persona o perro >75%** → envía `'P'` por Bluetooth + TTS "Intruso detectado"
4. Si está despejado o detecta gato/objeto seguro → envía `'G'` por Bluetooth

## Requisitos del teléfono
- Android 8+ (API 26+)
- Cámara trasera
- Bluetooth (para comunicación con Arduino)
- Activar "Instalar apps de orígenes desconocidos" para instalar el APK

## 📥 Cómo obtener el APK
1. Ve a **Actions** en este repositorio → "Build APK Guardian Robot"
2. Dale a **Run workflow** (espera 3-5 min)
3. Baja el APK de **Artifacts** (`guardian-robot-debug`)
4. En tu Redmi: activa orígenes desconocidos → instala el APK

## 📱 Cómo usar en el teléfono
1. **Empareja el módulo Bluetooth** (Ajustes → Bluetooth → busca "HC-05" o similar, PIN 1234)
2. Abre la app → presiona **INICIAR**
3. Concede permisos de **Cámara** y **Bluetooth**
4. Apunta el teléfono al área a vigilar

## 🤖 Modelo usado
- **EfficientDet-Lite0** (6.2 MB, cuantizado uint8)
- Entrenado en **COCO** (90 clases: persona=1, perro=17, gato=16)
- Se descarga automáticamente en GitHub Actions

## 🔧 Código Arduino (bloques / serial simple)
```cpp
void loop() {
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'P') {
      // PELIGRO: activar alarma, mover servo, buzzer, etc.
    } else if (c == 'G') {
      // SEGURO: apagar alarma, estado tranquilo
    }
  }
}
```

## 🏗️ Estructura del proyecto
```
app/src/main/java/com/guardian/robot/MainActivity.kt  ← Código principal
app/src/main/res/layout/activity_main.xml              ← Interfaz
.github/workflows/build-apk.yml                        ← Build automático
```
