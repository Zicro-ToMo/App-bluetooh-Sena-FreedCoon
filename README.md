# MotoLink 🏍️

**Puente de voz Bluetooth entre dos intercomunicadores de moto**

Diseñado específicamente para conectar un **FreedConn** y un **Sena 10** usando un Samsung Galaxy S24 Ultra como puente de audio en tiempo real.

---

## ¿Cómo funciona?

```
[FreedConn] ──HFP──► [S24 Ultra: MotoLink] ──HFP──► [Sena 10]
                            │
                     AudioRecord (SCO in)
                     AudioTrack  (SCO out)
                     Loopback engine @ 16kHz
```

El teléfono se conecta a ambos dispositivos vía perfil HFP (Hands-Free Profile).
El motor de audio captura voz desde un canal SCO y la retransmite al otro.

---

## Requisitos

- **Teléfono**: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, BT 5.3) — recomendado
- **Android**: 12+ (API 31+)
- **Dispositivos BT**: FreedConn y Sena 10, **emparejados previamente** en Ajustes del teléfono
- **Permisos**: Bluetooth, Micrófono (se solicitan al abrir la app)

---

## Instalación desde código fuente

### 1. Clonar / descomprimir el proyecto

```bash
cd MotoLink
```

### 2. Abrir en Android Studio

- Android Studio Hedgehog o superior
- File → Open → selecciona la carpeta `MotoLink`
- Espera la sincronización de Gradle

### 3. Conectar el S24 Ultra por USB

- Activar **Modo desarrollador**: Ajustes → Información del teléfono → toca 7 veces "Número de compilación"
- Activar **Depuración USB**

### 4. Build & Install

```bash
./gradlew installDebug
```

O usa el botón ▶ de Android Studio.

---

## Uso paso a paso

1. **Empareja primero** el FreedConn y el Sena 10 en Ajustes → Conexiones → Bluetooth del S24 Ultra
2. Abre MotoLink
3. Concede permisos de Bluetooth y Micrófono
4. Toca **Dispositivo A** → selecciona FreedConn
5. Toca **Dispositivo B** → selecciona Sena 10
6. Presiona **▶ Iniciar Puente**
7. La app queda en segundo plano (notificación persistente). El puente sigue activo aunque cierres la app.
8. Para detener: notificación → "Detener" o vuelve a la app y presiona **⏹ Detener Puente**

---

## Arquitectura

```
app/
├── model/
│   └── BtDeviceInfo.kt          # Estado de cada dispositivo
├── bluetooth/
│   └── MotoLinkBluetoothManager.kt  # HFP proxy + SCO + BroadcastReceivers
├── audio/
│   └── AudioBridgeEngine.kt     # AudioRecord → AudioTrack loopback @ 16kHz
├── service/
│   └── AudioBridgeService.kt    # Foreground service (WakeLock, notificación)
└── ui/
    └── MainActivity.kt          # UI: selector de dispositivos, VU meter, estado
```

---

## Parámetros de audio

| Parámetro | Valor |
|---|---|
| Sample rate | 16.000 Hz (wideband) |
| Encoding | PCM 16-bit |
| Channels | Mono |
| Audio source | `VOICE_COMMUNICATION` |
| Audio usage | `USAGE_VOICE_COMMUNICATION` |
| Buffer | ~80ms |

---

## Limitaciones conocidas

| Limitación | Descripción |
|---|---|
| SCO único | Android solo activa un canal SCO a la vez. El puente funciona en modo half-duplex rápido |
| Latencia | Esperada 60–150ms según chipset y distancia BT |
| Batería | El WakeLock + BT activo consume ~15–25% por hora |
| Alcance BT | El teléfono debe estar en rango de ambos dispositivos simultáneamente |

---

## Troubleshooting

**"No hay dispositivos emparejados"**
→ Ve a Ajustes del teléfono y empareja manualmente los intercomunicadores primero.

**SCO no conecta**
→ Asegúrate de que ninguna llamada activa esté usando el micrófono.
→ Reinicia Bluetooth en el teléfono.

**Audio entrecortado**
→ Acerca el teléfono a ambos intercomunicadores.
→ Desactiva otras conexiones BT (smartwatch, auriculares, etc.).

---

## Licencia

MIT — uso libre para proyectos personales.
