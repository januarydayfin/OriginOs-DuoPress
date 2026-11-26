# Double Power Key

![Android](https://img.shields.io/badge/Android-7.1%2B%20(API%2025%2B)-3DDC84?logo=android&logoColor=white)
![OS](https://img.shields.io/badge/ROM-OriginOS%20%7C%20FuntouchOS-4285F4)
![UI](https://img.shields.io/badge/UI-Material%20You%203%20(Dynamic%20Color)-A8C7FA)
![Root](https://img.shields.io/badge/Root-Not%20Required-brightgreen)
![Shizuku](https://img.shields.io/badge/Shizuku-Only%20for%20Shortcuts-orange)
![APK Size](https://img.shields.io/badge/Release%20APK-7.56%20MB-informational)
![Languages](https://img.shields.io/badge/Languages-Русский%20%7C%20English-blue)

**Double Power Key** (`com.vivotomirpay.stub`) — приложение для смартфонов **Vivo** и **iQOO** (прошивки **OriginOS** и **FuntouchOS**), которое переназначает **двойное нажатие кнопки питания** со стандартного китайского кошелька **Vivo Wallet** на:

- запуск **Mir Pay**, **СБПэй** или **любого установленного приложения**;
- переключение **фонарика**;
- **запуск камеры** (в том числе с заблокированного экрана);
- **запуск/отключение VPN** — **Happ** или **incy** (без root, официальные механизмы приложений);
- **Circle to Search** (как в MiCTS);
- **запуск ярлыков приложений** — manifest, dynamic и pinned shortcuts;
- **настраиваемое меню действий** — панель, куда можно добавить и действия, и приложения.

**Основные функции работают без root и Shizuku**, без удаления системных приложений. Для действия **«Ярлыки»** требуются **Android 11+ и Shizuku, запущенный через ADB без root (UID shell)**; root и Sui не поддерживаются. Интерфейс на Jetpack Compose и Material 3 следует светлой/тёмной теме системы: динамические цвета обоев на Android 12+, стандартные палитры Material 3 на более ранних версиях. Русский и английский выбираются по языку системы.
---

## Интерфейс Compose

Вкладки и вложенные экраны переключаются без анимации.

- **Действия** — обработчик кнопки питания, выбор действия двойного нажатия, отдельное действие на экране блокировки и редактор меню. Редактор открывается внутри приложения, а панель запуска действий остаётся отдельным окном.
- **Настройки** — вибрация, выбор VPN-клиента и настройки ассистента.
- **Диагностика** — сведения об обработчике кнопки, тест действия и журнал последнего запуска. Тест в приложении не проверяет физическую кнопку Vivo/iQOO.

## ✨ Что нового в 2.2.0 (до миграции)

- 🔗 **Ярлыки приложений** — новая плитка в диалоге «Действие двойного нажатия»: все launcher-приложения с поиском, раскрытие в manifest/dynamic/pinned shortcuts, сохранение выбора и запуск по двойному нажатию. Требуется **Android 11+ и Shizuku через ADB без root**; поддерживается холодный запуск целевого приложения.
- 📐 **Отступ под системные панели** — главный экран учитывает status bar и display cutout, заголовок больше не перекрывается фронтальной камерой.

---

## ✨ Что нового в 2.1.4 (история)

- 📱 **Редизайн Material You 3** — карточки, чипы, переключатели, ползунки, диалоги; динамические цвета из обоев на Android 12+ (палитра резолвится из системных ресурсов и подбирается по светлоте — корректно на любых прошивках), статичная палитра M3 на Android 11 и старше; светлая и тёмная темы следуют системе.
- 🌃 **Отдельное действие на заблокированном экране** — только **фонарик** или **камера** (камера открывается через secure-интент, как по штатному ярлыку локскрина). Настройка **отключается** — и на локскрине выполняется то же действие, что и на разблокированном.
- 📷 **Действие «Камера»** — системный интент камеры; у большинства камер включён `showWhenLocked`, поэтому съёмка открывается прямо с экрана блокировки, как по штатному ярлыку.
- 🛡 **Действие «VPN»** — переключает подключение VPN-клиента **в фоне, без открытия приложений**:
  - **Happ** (`su.happ.proxyutility`, старый пакет `com.happproxy` тоже поддержан) — через полупрозрачный системный перехватчик deeplink **`happ://toggle_without_ui`** (официальный путь «без интерфейса»: перехватчик принимает только хосты `*_without_ui`) и broadcast в экспортированный виджет-приёмник `*.action.widget.click`;
  - **incy** (`llc.itdev.incy`) — основной путь: broadcast **`llc.itdev.incy.TOGGLE`** в экспортированный `VpnIntentReceiver` (полностью в фоне); резервный — скрытая shortcut-команда `VOICE_TOGGLE` (тема без видимого UI);
  - выбор клиента: **Авто** (первый установленный) / **Happ** / **incy**; показывается статус «VPN активен / не активен» (публичный API `TRANSPORT_VPN`).
- 📳 **Настройки вибрации** — вкл/выкл, **длительность импульса** (20–500 мс) и **паттерн**: один импульс / двойной / длинный.
- 🗂 **Меню действий** — если действием выбрано «Меню действий», двойное нажатие открывает настраиваемую панель, которая **плавно выезжает снизу** (bottom sheet в стиле M3): иконки приложений в полном цвете, компактные пункты. Состав настраивается в отдельном экране «Настройка меню»: «Добавить действие…» / «Добавить приложение…» (с поиском), стрелки порядка, крестик удаления. Меню **работает поверх экрана блокировки**: фонарик, камера и VPN переключаются без разблокировки.
- 🌍 **Английский язык** — добавлены ресурсы `values-en` в дополнение к `values` (русский).
- 🔊 и всё из версии 1.3: пресеты Mir Pay / СБПэй / ключ Vivo Wallet / Circle to Search, выбор любого приложения с поиском, журнал последнего срабатывания, вызов системного диалога «Всегда».

---

## ✨ Основные возможности

- ⚡ **Запуск без промежуточных окон** — прозрачная [`SwipeEntryActivity`](app/src/main/kotlin/com/vivotomirpay/stub/SwipeEntryActivity.kt) без анимации перенаправляет вызов в выбранное приложение или действие и закрывается.
- 💳 **Не сбивает приложение бесконтактной оплаты** — заглушка не регистрирует фиктивный HCE-сервис, поэтому **Mir Pay** остаётся основным платёжным средством NFC в системе.
- 🔦 **Пресет «Фонарик»** — спец-значение `special:torch`. Вспышка переключается вкл/выкл, состояние синхронизируется через `CameraManager.setTorchMode`.
- 🔍 **Пресет «Circle to Search»** — спец-значение `special:circle_to_search`. Тот же механизм, что в проекте [parallelcc/MiCTS](https://github.com/parallelcc/MiCTS): прямой вызов скрытого системного API `IVoiceInteractionManagerService.showSessionFromSession()` через reflection + библиотеку `org.lsposed.hiddenapibypass`. **Не требует root, не требует accessibility, не требует разрешений.**
- 🎯 **Гибкий выбор действия** — пресеты специальных действий, поиск по приложениям и ручной ввод `packageName`. Mir Pay, СБПэй и Vivo Wallet выбираются как обычные приложения.
- 🔗 **Ярлыки приложений** — в пикере «Действие двойного нажатия» пункт **«Ярлыки»** открывает список launcher-приложений. Приложения раскрываются в списки **manifest**, **dynamic** и **pinned** shortcuts; выбор сохраняется, а двойное нажатие запускает выбранный ярлык. Полный доступ реализован через **Shizuku**, запущенный через **ADB без root (UID shell)**, на **Android 11+**; root и Sui не поддерживаются. Для остальных действий Shizuku не нужен.
- 🛡 **Безопасный журнал** — лог последнего срабатывания обрезается до 1000 символов, значения extras — до 100.
- 🧭 **Понятные сообщения об ошибках** — все сбои пишутся в журнал вместо тихих `catch`.
- 🔒 **`android:allowBackup="false"`** — бэкапить здесь нечего, а восстановленный бэкап мог бы подменить целевой пакет.

---

## 🔍 Как это работает

На смартфонах Vivo / iQOO при двойном нажатии кнопки питания система формирует и отправляет неявный системный `Intent`:

```text
Action:   vivo.intent.action.NFC_BUS_SWINGSPLASH
Category: android.intent.category.DEFAULT
Extras:   lb_from = "2"
```

По умолчанию в китайских прошивках OriginOS этот интент обрабатывается системным кошельком `com.vivo.wallet` (`com.vivo.pay.swing.activity.NewSwipeActivity`) или `com.vivo.seclient`.

**Double Power Key** объявляет экспортированную Activity [`SwipeEntryActivity`](app/src/main/kotlin/com/vivotomirpay/stub/SwipeEntryActivity.kt) с тем же `<intent-filter>` и системными метаданными Vivo в [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

```xml
<meta-data android:name="com.vivo.wallet.support.typeswing" android:value="true" />
<meta-data android:name="com.vivo.wallet.nfc.SupportDoubleClick" android:value="true" />
<meta-data android:name="com.vivo.wallet.nfc.CommonSupportDoubleClick" android:value="true" />
<meta-data android:name="com.vivo.wallet.nfc.supportVersion" android:value="2" />
<meta-data android:name="com.vivo.pay.nfc.supportVersion" android:value="1" />
```

> ⚠️ Эти же метаданные продублированы внутри самой `SwipeEntryActivity` — некоторые сборки OriginOS / FuntouchOS ищут их именно на компоненте-обработчике.

Дальше заглушка определяет состояние экрана (`KeyguardManager.isKeyguardLocked()`):

- **экран разблокирован** → выполняется действие из карточки «Действие двойного нажатия»;
- **экран заблокирован, настройка «Отдельное действие» включена** → выполняется действие из карточки «Заблокированный экран»;
- **экран заблокирован, настройка выключена** → выполняется то же действие, что и на разблокированном экране.

Исполнение действий вынесено в общий класс [`ActionRunner`](app/src/main/kotlin/com/vivotomirpay/stub/ActionRunner.kt), которым пользуются и перехватчик, и меню действий. Переключение VPN инкапсулировано в [`VpnController`](app/src/main/kotlin/com/vivotomirpay/stub/VpnController.kt).

---

## 🚀 Установка и настройка

### 1. Установка APK
Установите готовый подписанный APK (или соберите сами — см. ниже).

### 2. Включение двойного нажатия в настройках Vivo
- **OriginOS / FuntouchOS:** `Настройки` → `Другие сети и подключения` → `NFC` → **«Дважды нажмите кнопку питания»** (или в настройках `Vivo Wallet`).

### 3. Настройка действий
1. Откройте **Double Power Key → Действия → Двойное нажатие** и выберите приложение или действие.
2. Откройте **«Заблокированный экран»**, чтобы включить и выбрать отдельное действие (по умолчанию фонарик) или вернуться к обычному действию.
3. На вкладке **«Настройки»** настройте вибрацию и VPN-клиент (Happ / incy / авто).
4. В **«Действия → Меню действий»** добавляйте действия и приложения, меняйте их порядок стрелками и открывайте предпросмотр.

### 4. Настройка действия «Ярлыки» (Android 11+, требуется Shizuku)
1. Убедитесь, что на устройстве **Android 11 или новее**, и установите **Shizuku**.
2. Запустите сервис Shizuku через **ADB** или **беспроводную отладку** по инструкции Shizuku. Root не нужен: сервис работает с **UID shell**. Root/Sui для этой фичи не поддерживаются.
3. В **Double Power Key → Действия → Двойное нажатие** выберите **«Ярлыки»**.
4. Выдайте **Double Power Key** разрешение Shizuku.
5. Выберите launcher-приложение, раскройте его и нажмите на нужный **manifest**, **dynamic** или **pinned** shortcut. Выбор сохранится и будет использоваться при двойном нажатии.

Остальные действия по-прежнему работают без root и Shizuku. Если сервис Shizuku остановлен или разрешение отозвано, запуск ярлыка завершится ошибкой — её описание видно в **журнале Double Power Key**.

### 5. Назначение обработчиком по умолчанию
1. На вкладке **«Действия»** нажмите **«Настроить кнопку»** (если кнопка доступна) или дважды нажмите кнопку питания. Если уже выбран другой обработчик, сначала сбросьте его назначение в системных настройках приложения-обработчика.
2. Если система показала окно выбора, выберите **Double Power Key** и **«Всегда»**.
3. Проверьте, какой обработчик показывает приложение. Статус показывает результат разрешения Intent, а не подтверждает, что вы нажали «Всегда».

---

## 📁 Структура проекта

Один модуль `:app`; основной код находится в [`app/src/main/kotlin/com/vivotomirpay/stub/`](app/src/main/kotlin/com/vivotomirpay/stub/), без production Java.

| Путь от `com/vivotomirpay/stub/` | Назначение |
|---|---|
| `MainActivity.kt`, `AppDependencies.kt`, `ui/navigation/` | Хост Compose, сборка зависимостей и Navigation Compose |
| `actions/`, `settings/`, `diagnostics/` | Три корневые вкладки и их состояние/намерения в ViewModel (MVI) |
| `picker/`, `menu/` | Выбор приложений/ярлыков/действий, редактор и содержимое панели меню |
| `ui/theme/`, `ui/common/` | Тема Material 3 и общие элементы |
| `data/`, `PrefsHelper.kt`, `ShortcutAction.kt` | Снимок и репозиторий общих настроек, совместимое хранилище и кодек ярлыков |
| `platform/`, `ShizukuShortcuts.kt`, `VpnController.kt`, `HiddenApiCompat.kt`, `Vibe.kt` | Системные запросы, каталог, запуски и адаптеры |
| `SwipeEntryActivity.kt`, `MenuActivity.kt`, `ActionRunner.kt` | Точка входа Vivo, отдельное окно запуска меню и общий исполнитель действий |

[`app/src/main/res/`](app/src/main/res/) содержит RU/EN-строки и XML-темы системных Activity, включая `TransparentSwipeTheme` и `MenuTheme`.

---

## 🔧 Сборка APK

В репозитории есть **Gradle Wrapper** — Gradle ставить не обязательно.

### Требования
- **JDK 17+**; версии Gradle, AGP и Kotlin закреплены в репозитории.
- **Android SDK**: `platforms;android-36` (`compileSdk 36`) и Build Tools. Приложение устанавливается начиная с API 25 (`minSdk`), `targetSdk` — 35.
- `ANDROID_HOME` или `local.properties` со строкой `sdk.dir=/path/to/AndroidSDK`

```bash
bash gradlew :app:assembleRelease     # release (debug-ключ по умолчанию)
bash gradlew :app:assembleDebug       # debug (отдельный ID с суффиксом .debug)
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Release APK: `app/build/outputs/apk/release/app-release.apk`. В локальной сборке без минификации и удаления неиспользуемых ресурсов он вырос с **127 508 до 7 558 790 байт** (**+7 431 282 байта**, итоговый размер около 7,56 МБ). Сравнивались одинаковые подписанные `release`-варианты до и после подключения Kotlin/Compose и AndroidX, не APK из опубликованного релиза. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

[Матрица проверки миграции](docs/superpowers/plans/2026-11-25-kotlin-compose-mvi-verification.md) отделяет результаты сборки, манифеста и эмулятора от сценариев, требующих реального Vivo/iQOO.

### Подпись собственным ключом (опционально)

```bash
export VVTM_KEYSTORE=/path/to/release.jks
export VVTM_KEYSTORE_PASSWORD=******
export VVTM_KEY_ALIAS=release
export VVTM_KEY_PASSWORD=******
bash gradlew :app:assembleRelease
```

Без них используется `debug.keystore` (пароль `android`, alias `androiddebugkey`) — достаточно для локальной установки.

Также доступны скрипты: `./build_apk.sh` (Linux/macOS) и `.\build_apk.ps1` (Windows).

---

## 🔒 Безопасность

- **`android:allowBackup="false"`** — восстановленный бэкап мог бы подменить целевой пакет.
- **Экспортированная `SwipeEntryActivity` принимает интенты от любого приложения**, поэтому значения extras пишутся в `SharedPreferences` только как обрезанные строки (≤ 100 символов на значение, ≤ 1000 на весь лог).
- **VPN-действие не хранит никаких учётных данных** и не требует разрешений — оно лишь отправляет в установленный клиент тот же интент, что и его собственные виджет/шорткат.
- Меню действий показывается над экраном блокировки только по явному выбору пользователя.
- **Shizuku используется только действием «Ярлыки»** для получения и запуска shortcuts на Android 11+. Доступ выдаётся отдельным разрешением Double Power Key; сервис запускается через ADB без root (UID shell), root/Sui не поддерживаются. Остальные функции не требуют ни root, ни Shizuku.

---

## 🌐 English (short)

**Double Power Key** remaps the power-button double press on Vivo/iQOO (OriginOS/FuntouchOS) to an app, flashlight, camera, Happ/incy VPN toggle, Circle to Search, an app shortcut, or a customizable action menu. You can choose a separate flashlight/camera action for the lock screen. Only **Shortcuts** (manifest, dynamic, pinned) requires **Android 11+ and Shizuku started via ADB without root (shell UID)**; root/Sui are not supported.

The Compose Material 3 UI has three tabs: **Actions** (button setup, pickers, lock screen and menu editor), **Settings** (vibration, VPN client and assistant), and **Diagnostics** (handler, test action and log). It follows the system's light/dark theme and locale (Russian/English), with dynamic colors on Android 12+. The in-app test does not exercise the physical button.

Requires Android **7.1+ (API 25)**. Build with JDK 17+, SDK 36 and `bash gradlew :app:assembleRelease`. Production code is under [`app/src/main/kotlin/com/vivotomirpay/stub/`](app/src/main/kotlin/com/vivotomirpay/stub/). The locally measured, unminified release APK grew from **127,508** to **7,558,790 bytes** (**+7,431,282 bytes**); see the [verification matrix](docs/superpowers/plans/2026-11-25-kotlin-compose-mvi-verification.md) for scope and device limitations.

---

## 📄 Лицензия

Проект распространяется свободно в ознакомительных и личных целях (MIT License).
