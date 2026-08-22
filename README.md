# Android Bluetooth Manager 🎧📱

**Приложение для расширенного управления подключаемыми Bluetooth-устройствами и наушниками на Android**

## Возможности

- 📋 Список всех спаренных Bluetooth-устройств
- 🔋 Отображение уровня батареи (где поддерживается системой)
- 🔗 Быстрое подключение / отключение устройств
- 🔊 Управление громкостью медиа и звонков
- 🎵 Медиа-контролы (play/pause, next/prev)
- 📶 Статус подключения в реальном времени
- 🛡️ Современные runtime-разрешения (Android 12+)
- 🎨 Красивый Material 3 интерфейс на Jetpack Compose

## Скриншоты / Демо

Приложение показывает список устройств, их тип (наушники, колонка, телефон и т.д.), статус подключения и батарею.

## Как собрать APK

### Самый простой способ (рекомендуется)

1. Установите [Android Studio](https://developer.android.com/studio) (Hedgehog или новее).
2. File → Open → выберите папку этого репозитория.
3. Дождитесь синхронизации Gradle.
4. Build → Build Bundle(s) / APK(s) → Build APK(s).
5. APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

### Через командную строку (если установлен Android SDK)

```bash
./gradlew assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`

### Release-сборка (подписанный APK)

1. Создайте keystore:
   ```bash
   keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias bluetoothmanager
   ```
2. Добавьте в `app/build.gradle.kts` секцию `signingConfigs` и укажите пути.
3. `./gradlew assembleRelease`

## Требования

- minSdk 26 (Android 8.0)
- targetSdk 34
- Kotlin 1.9+
- Jetpack Compose

## Разрешения

Приложение запрашивает:
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_SCAN`
- `ACCESS_FINE_LOCATION` (для сканирования на старых версиях Android)

## Архитектура

- **UI**: Jetpack Compose + Material 3
- **Bluetooth**: `BluetoothAdapter`, `BluetoothDevice`, `BluetoothHeadset`, `BluetoothA2dp`
- **Архитектура**: простой MVVM (ViewModel + StateFlow)

## Ограничения

Полный контроль ANC, EQ, жестов и специфических функций наушников (Sony, Bose, Huawei, Samsung и т.д.) требует reverse-engineering проприетарных протоколов каждой марки. Это приложение использует только стандартные Android Bluetooth API.

Для глубокой поддержки конкретных моделей смотрите проекты вроде [FreeBuddy](https://github.com/TheLastGimbus/FreeBuddy).

## Лицензия

MIT

---

Сделано с помощью Grok 🚀
