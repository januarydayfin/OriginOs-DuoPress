# Remove HiddenApiBypass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Удалить HiddenApiBypass, перевести ярлыки на публичный LauncherApps через Shizuku UserService и снять поддержку Circle to Search.

**Architecture:** Сохранить подтверждённый на vivo путь через Shizuku UserService, но держать одно соединение на процесс DuoPress и использовать его для списка, иконок и запуска. Обе Activity выполняют общий suspend-диспетчер, который не приостанавливается для обычных действий. Старый ID Circle to Search остаётся только ради совместимости сохранённых назначений.

**Tech Stack:** Android/Kotlin, Compose, AIDL, Shizuku 13.1.5, Kotlin Coroutines, JUnit и AndroidX instrumentation.

**Spec:** [`docs/superpowers/specs/2026-11-26-remove-hidden-api-bypass-design.md`](../specs/2026-11-26-remove-hidden-api-bypass-design.md).

## Global Constraints

- Только `experiment/no-hidden-api-bypass`. Не сбрасывать существующий diff эксперимента и не менять `main`.
- Android 11+; Shizuku API 13+ от ADB shell; root и Sui не поддерживаются. `minSdk 25`, `targetSdk 36`; без новых разрешений, ролей, зависимостей для обхода или изменений манифеста.
- Подключение не блокирует UI, имеет тайм-аут 5 секунд и восстанавливается после отключения Shizuku. Без постоянного демона.
- Android 11–13: `LauncherApps.startShortcut`; Android 14+: `PendingIntent` ярлыка.
- Формат `shortcut:<package>:<id>:<label>`, ключи, сохранённые назначения, порядок и идентичность ярлыков не меняются. Старый `special:circle_to_search` не перезаписывать.
- До изменения существующих символов — GitNexus upstream и предупреждение о HIGH/CRITICAL; перед каждым коммитом — `gitnexus_detect_changes(scope: "staged")`.

## Review Focus

1. Холодное меню: второй тап и сигналы скрытия до завершения попытки не запускают ярлык повторно и не закрывают окно преждевременно → задача 1, `shortcutDelaysCloseAndDispatchesOnlyOnce`.
2. Shizuku отключился или не ответил: старый Binder не используется, поздняя привязка не оживляет отменённую операцию → задача 1, `reconnectsAfterDeathAndIgnoresTimedOutCallback`.
3. Shizuku v12 установлен и разрешение есть: показать требование v13 до загрузки списка → задача 1, `oldShizukuRequiresUpgradeWithoutLoading`.
4. Иконка повреждена, отсутствует или очень большая: не превышать `128×128`, использовать fallback без потери строки → задача 1, `shortcutIconIsBoundedOrFallsBack`.
5. Старое меню и пресет содержат удалённый ID: данные остаются бит-в-бит, но действие не запускается и не предлагается для нового назначения → задача 2, `legacyCircleShowsUnavailableWithoutMutatingSettings`.

---

### Задача 1: Один backend Shizuku для всех ярлыков

**Файлы:**
- `app/src/main/aidl/com/krayapp/duopress/platform/IShortcutExperiment.aidl`, `app/src/main/kotlin/com/krayapp/duopress/platform/ShortcutExperiment{Client,Service}.kt` — довести эксперимент до `IShortcutService`, `ShortcutServiceClient`, `ShortcutUserService`.
- `app/src/main/kotlin/com/krayapp/duopress/ShizukuShortcuts.kt`, `platform/ShortcutGateway.kt`, `picker/ShortcutPicker{ViewModel,Screen}.kt`, `app/src/main/res/values{,-en}/strings.xml` — проверка доступа, список, иконки, обновление текста требования v13.
- `app/src/main/kotlin/com/krayapp/duopress/{ActionRunner,SwipeEntryActivity,MenuActivity}.kt`, `menu/MenuSelection.kt` — общий запуск и ожидание завершения выбора.
- Тесты: `app/src/test/kotlin/com/krayapp/duopress/platform/ShortcutServiceConnectionTest.kt`, `menu/MenuSelectionTest.kt`, `picker/ShortcutPickerViewModelTest.kt`; `app/src/androidTest/kotlin/com/krayapp/duopress/platform/{PlatformAdaptersTest,SwipeEntryContractTest}.kt`.

**Интерфейсы:** `ShortcutServiceClient.getShortcuts(packageName: String): List<ShortcutInfo>`, `getShortcutIcon(shortcut: ShortcutInfo, densityDpi: Int, sizePx: Int): Bitmap?`, `startShortcut(packageName: String, shortcutId: String): Unit` — `suspend`. `ShortcutServiceConnection.awaitService(): IShortcutService` — `suspend`; инъецировать `bind: (ServiceConnection) -> Unit`, `unbind: (ServiceConnection) -> Unit` и `timeoutMs: Long = 5_000`. `ShizukuShortcuts.Status.PRE_V13` и `ShortcutAccess.PreV13` вместо `PreV11`. `ActionRunner.runAction(context: Context, actionId: String?, sourceIntent: Intent?): String` — `suspend`; `MenuSelection` получает `dispatch: (String, () -> Unit) -> Unit`, где второй параметр сообщает о завершении. До задачи 2 сохранить старый путь CTS после скрытия.

- [ ] Добавить тесты соединения: два успешных запроса используют один `bind`; `reconnectsAfterDeathAndIgnoresTimedOutCallback` — после 5000 мс без callback возвращается ошибка, поздний callback от первой попытки игнорируется, следующая выдаёт только новый Binder; после его смерти третий запрос переподключается.
- [ ] Добавить `oldShizukuRequiresUpgradeWithoutLoading`: состояние `PreV13`, вызовов чтения ярлыков — 0. Сохранить существующую проверку потери разрешения перед сохранением выбора.
- [ ] Добавить `shortcutDelaysCloseAndDispatchesOnlyOnce`: до callback события `["dispatch:shortcut:pkg:home:Home"]`; повторный тап и `onHidden()` ничего не добавляют; callback и `onHidden()` дописывают ровно `["close", "finish"]`. Сохранить проверку одного немедленного запуска для обычного действия и закрытия по Back без запуска.
- [ ] Запустить `./gradlew :app:testDebugUnitTest`; ожидается красный шаг на новом контракте. Не удалять тесты сохранения и запуска только для устранения ошибок компиляции.
- [ ] Переименовать экспериментальные AIDL и классы; сохранить проверку UID DuoPress в Binder-методах и `.daemon(false)`. В активной привязке держать `ServiceConnection` до смерти Binder/процесса; снимать неуспешную или отменённую привязку и не принимать её callback как результат нового запроса. Проверка статуса и операции IPC — вне Main. Никакого fallback к reflection.
- [ ] Добавить `Bitmap getShortcutIcon(in ShortcutInfo shortcut, int densityDpi, int sizePx)` в AIDL. В сервисе получить `LauncherApps.getShortcutIconDrawable`, отрисовать с сохранением пропорций в программный bitmap с обеими сторонами не более `128 px`, вернуть `null`, если значка нет. В gateway сохранить ленивую загрузку по видимой строке и fallback пикера к иконке приложения. Инструментальный сценарий `shortcutIconIsBoundedOrFallsBack`: изображение `512×256` не превышает лимит, при `null` строка и fallback остаются. Не передавать bitmap для всего каталога одним ответом.
- [ ] Удалить из `ShizukuShortcuts` создание `LauncherApps` через скрытый конструктор и `ShizukuBinderWrapper`; заменить методы новым клиентом. Для Shizuku API <13 показывать локализованное требование v13; Android <11 и неверный UID по-прежнему диагностируются, разрешения не меняются.
- [ ] Подключить общий suspend `ActionRunner.runAction` к обеим Activity, убрать `runShortcutExperiment`; не проглатывать `CancellationException`. Для запуска ярлыка UI ждёт окончания отправки, затем закрывается; обычные действия выполняются до первого приостановления и сохраняют прежний порядок, включая задержку камеры `450L`. В существующем отложенном callback CTS временно вызвать синхронный `triggerCircleToSearch` напрямую, чтобы не привязывать его к уже уничтоженной Activity. Адаптировать прямые вызовы в `SwipeEntryContractTest` к `suspend`, сохранив все проверки статуса и входящего интента.
- [ ] Запустить `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`. На изолированном эмуляторе подтвердить список, иконку, запуск из `SwipeEntryActivity` и меню; проверить, что приложение не меняет сохранённые значения, если Shizuku недоступен. После проверки diff и GitNexus — отдельный локальный коммит backend.

### Задача 2: Удаление Circle to Search, зависимости и устаревших настроек

**Файлы:**
- `app/build.gradle`, удалить `app/src/main/kotlin/com/krayapp/duopress/HiddenApiCompat.kt`.
- `app/src/main/kotlin/com/krayapp/duopress/{ActionRunner,PrefsHelper,MenuActivity}.kt`, `actions/{ActionsScreen,ActionLabelResolver,ActionsViewModel}.kt`, `picker/ActionPicker{ViewModel,Screen}.kt`, `menu/{ActionSheet,MenuEditorScreen,MenuRules,MenuSelection}.kt`.
- `app/src/main/kotlin/com/krayapp/duopress/settings/Settings{Screen,ViewModel}.kt`, `platform/{DeviceStatusProbe,PlatformRequest,PlatformRequestHost}.kt` — удалить секцию ассистента и ставший неиспользуемым запрос.
- `app/src/main/res/values{,-en}/strings.xml`, `README.md`; не переписывать исторические документы.
- Тесты: `app/src/test/kotlin/com/krayapp/duopress/{menu/MenuRulesTest,menu/MenuSelectionTest,picker/ActionPickerViewModelTest,actions/ActionsViewModelTest,settings/SettingsViewModelTest}.kt`, `app/src/androidTest/kotlin/com/krayapp/duopress/{ComposeAppIntegrationTest,data/SettingsCompatibilityTest,platform/SwipeEntryContractTest}.kt`.

**Интерфейсы:** сохранить `PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH = "special:circle_to_search"` только для чтения старого назначения, запрета повторного добавления и статуса. Общий ресурс `R.string.action_circle_unavailable`: «Circle to Search — недоступно» / “Circle to Search — unavailable”.

- [ ] Добавить `legacyCircleShowsUnavailableWithoutMutatingSettings`: старый ID в `target_package` и существующем массиве меню остаётся без изменений после открытия и выполнения; статус и журнал содержат `action_circle_unavailable`; тестовый хост не получает запрос на запуск Activity. Для отсутствующего/некорректного JSON ожидаются `[special:torch, special:camera, special:vpn]` и отсутствие записи на чтении.
- [ ] Обновить тесты выбора: пресеты — Torch, Camera, Vpn и Menu; добавление `special:circle_to_search` возвращает `NoChange`; старый элемент остаётся доступным для удаления. На экран настроек больше не попадает кнопка ассистента; заменить тест старых fallback-интентов проверкой отсутствия секции, не удаляя VPN, виброотклик и диагностику из проверок.
- [ ] Запустить `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest`; ожидается красный шаг на новом наборе пресетов/ресурсе.
- [ ] Добавить статус и убрать новый выбор/дефолт; в существующих назначениях использовать общий статус как подпись, не маскировать под другое действие и не фильтровать данные из редактора.
- [ ] Удалить `triggerCircleToSearch`, его чтение ассистента, задержку 550 мс и `onWindowDetached` из координатора; сохранить защиту от повторного выбора. Удалить секцию и команду ассистента, поля статуса, ресурсы, используемые только ими, `HiddenApiCompat` и строку зависимости. Обновить README и RU/EN подсказки для Android 11+ и Shizuku API 13+.
- [ ] Запустить unit-тесты, debug/release и test APK: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`. Отдельно `./gradlew :app:dependencyInsight --dependency hiddenapibypass --configuration debugRuntimeClasspath` и тот же отчёт для `releaseRuntimeClasspath`: оба не находят зависимость. Поиск в `app/src/main` и Gradle не находит `HiddenApiCompat`, `hiddenapibypass`, `IVoiceInteractionManagerService`, `ILauncherApps` или `setHiddenApiExemptions`.
- [ ] Прогнать `SwipeEntryContractTest`, `PlatformAdaptersTest`, `SettingsCompatibilityTest` и `ComposeAppIntegrationTest` на отдельном тестовом устройстве/эмуляторе. На vivo установить debug поверх эксперимента без сброса данных; проверить T‑Pay по физическому двойному нажатию после холодного старта, другой ярлык из меню, значок в пикере и обычные действия без Shizuku. Отметить отдельно сценарии, которые не удалось проверить. После проверки diff и GitNexus — отдельный локальный коммит удаления; без push и merge.
