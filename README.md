# CrowPlayer Companion — USB edition

Компаньон для CrowPlayer (Elecrow CrowPanel 1.28" HMI ESP32-S3): передаёт на панель
метаданные трека, обложки и настройки по USB-CDC вместо BLE. Панель для планшета —
обычное CDC-устройство: без pairing и сканирования.

Громкость управляется усилителем Hellion через UART на самой панели; компаньон
команду `volume` от панели игнорирует.

## Структура проекта

    .
    ├── .github/workflows/build.yml      # автосборка APK на GitHub Actions
    ├── app/
    │   ├── build.gradle.kts             # minSdk 26, targetSdk 34, без внешних зависимостей
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/ru/drdroid/crowplayer/companion/
    │       │   ├── MainActivity.kt      # экран настроек (ночь, яркости)
    │       │   ├── UsbCdcService.kt     # USB-CDC: reader/writer, permission
    │       │   ├── MediaBridge.kt       # медиасессии, meta, обложки, лайки
    │       │   └── Prefs.kt             # настройки в SharedPreferences
    │       └── res/                     # layout, иконка, строки, device_filter
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── gradle.properties

## Сборка

### На GitHub (без локальной Android Studio)
1. Создайте новый репозиторий на GitHub (например, `CrowPlayerCompanionUSB`).
2. Загрузите все файлы проекта в ветку `main` (см. «Выкладка на GitHub» ниже).
3. Workflow `Build APK` соберёт APK на каждый push.
4. Actions → последняя сборка → Artifacts → скачайте `CrowPlayerCompanion-debug`.

### Локально
Требуются JDK 17 и Android SDK 34:

    gradle assembleDebug
    # APK: app/build/outputs/apk/debug/app-debug.apk

## Первый запуск на планшете
1. Установите APK.
2. На главном экране нажмите «Разрешить доступ к уведомлениям →» и включите
   CrowPlayer Companion — доступ к медиасессиям.
3. В настройках батареи HarmonyOS отключите оптимизацию для приложения,
   иначе сервис уснёт при выключенном экране.
4. Подключите панель кабелем; при первом подключении разрешите доступ к USB.

## Протокол (JSON по строкам через USB-CDC)

Планшет → панель:
```json
{"type":"meta","title":"...","artist":"...","state":"playing","positionMs":37400,
 "durationMs":214000,"liked":false,"nightMode":false,"autoNight":true,
 "screenBrightness":75,"ledBrightness":42,"epochMs":1794022800000}
{"type":"art","total":12800}   // далее 12800 байт raw RGB565, 80x80, big-endian
{"type":"hello"}
```

Панель → планшет:
```json
{"cmd":"play_pause"} {"cmd":"next"} {"cmd":"prev"}
{"cmd":"like"}
{"cmd":"volume","delta":2}   // игнорируется: громкость на усилителе
```

## Ограничения
- Обложка берётся из MediaMetadata (ALBUM_ART); если плеер отдаёт только ART_URI (URL),
  загрузка по сети не реализована.
- Лайк через sendRatingEvent(heart) проверьте на живом железе с Яндекс Музыкой.
- `device_filter.xml` содержит vendor-id 0x303A (Espressif); при другом VID поправьте.
- Обложка 80x80 задана в MediaBridge (константа ART) — совпадает с прошивкой CrowPanel.
