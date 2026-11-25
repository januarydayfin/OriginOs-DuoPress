# Double Power Key — Kotlin, Compose and MVI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перевести Double Power Key на Kotlin и Material 3 Compose с тремя разделами варианта C, не нарушив совместимость обновления и работу двойного нажатия.

**Architecture:** Один модуль `:app`; экранные ViewModel с `UiIntent` и `StateFlow<UiState>`, типизированная Navigation Compose, один репозиторий настроек. Сначала новый UI работает с прежним Java-исполнителем, затем он и его зависимости переносятся в Kotlin без изменения контракта. Точка входа Vivo не зависит от UI.

**Tech Stack:** Gradle 8.9, AGP 8.7.0, JDK 17+, Kotlin 2.1.21 (Android, Compose compiler, Serialization), Material 3, Lifecycle, Navigation Compose, coroutines, JUnit 4 и Compose UI Test.

**Spec:** [Дизайн](../specs/2026-11-25-kotlin-compose-mvi-design.md) · [Архитектура](../specs/architecture.md).

## Global Constraints

- `applicationId com.vivotomirpay.stub`, debug-суффикс `.debug`, подпись, `minSdk 25`, `targetSdk 35`, `compileSdk 36`, `allowBackup=false`; без новых разрешений, сервисов и изменений signing/minification.
- Полное имя `com.vivotomirpay.stub.SwipeEntryActivity`, фильтр и метаданные Vivo на уровне приложения и Activity, прозрачная тема, параметры task/history и поведение на блокировке сохраняются. То же для task и темы `MenuActivity`.
- `vivo_swipe_stub_prefs`, все прежние ключи, значения и defaults, `special:*`, `app:*`, `shortcut:…`, порядок JSON-массива и `[]`; лимиты меню 16, журнала 1000 символов и каждого значения extras 100. Без DataStore или второго хранилища.
- Ярлыки требуют Android 11+ и Shizuku через ADB shell; остальные действия не зависят от Shizuku. Запуск действий не ждёт Compose или первой эмиссии `Flow`.
- RU/EN, темы системы, динамический цвет на API 31+, статичные схемы ниже; интерпретируемые TalkBack статусы, подписи, области касания ≥48 dp и корректные insets/IME.
- Для изменяемых существующих символов до правок: GitNexus upstream impact и сообщение о вызывающих, процессах и HIGH/CRITICAL. Индексировать выбранную рабочую копию через `gitnexus analyze --index-only` при отсутствии/устаревании; указывать её путь или уникальный alias. Перед каждым коммитом: `git diff --cached --check`, `gitnexus.detect_changes(scope="staged")` для выбранной копии, проверить staged-список. Не добавлять `.superpowers/`, релизные артефакты или чужие файлы.
- Build-команды используют `bash gradlew`: wrapper в git имеет режим `100644`. Исходная точка `89f14c7`: 11/11 JVM-тестов, lint успешен, release APK 127 580 байт; исходное предупреждение AGP 8.7.0 о compileSdk 36 известно. В worktree сохранить собственный baseline APK отдельно от итогового и сопоставить подпись; без `adb install` на личное устройство без разрешения.
- Три этапа одной зависимой миграции: **UI и совместимый адаптер** (1–6), **перенос системного слоя** (7–9), **сдача** (10). Каждый шаг оставляет сборку работоспособной; до шага 6 существующий `MainActivity` сохраняет рабочий fallback UI. Шаги не являются отдельными релизами.

## Review Focus

1. **Другой default-handler и единственный доступный handler:** первый открывает сведения о своём пакете, второй считается `ThisApp` без требования «Всегда». Задача 2.
2. **Уход из Shizuku-пикера с активным запросом:** результат не открывает закрытый экран и не перезаписывает новый выбор. Задача 4.
3. **Пакет исчез из списка или не отдаёт иконку:** сохранённый ID не теряется, поиск и ручной выбор доступны. Задача 4.
4. **Старое меню с дубликатами и `special:menu`:** не рендерим рекурсию, не запускаем пункт дважды, Circle to Search запускаем только после скрытия окна. Задача 5.
5. **Старый кодек и пустые значения:** точные percent-encoded данные ярлыка и `[]` выдерживают обновление; большой лог остаётся ограниченным. Задача 7.

## Карта файлов

- Новый Kotlin-код: `app/src/main/kotlin/com/vivotomirpay/stub/`. В дальнейшем `K/` означает этот путь, `J/` — `app/src/main/java/com/vivotomirpay/stub/`, `U/` — `app/src/test/kotlin/com/vivotomirpay/stub/`, `I/` — `app/src/androidTest/kotlin/com/vivotomirpay/stub/`.
- `K/ui/navigation`, `K/ui/theme`, `K/ui/common` — только Compose-оболочка и переиспользуемые визуальные элементы. `K/actions`, `K/settings`, `K/diagnostics`, `K/picker`, `K/menu` — состояние и UI соответствующего сценария.
- `K/data` — контракт настроек и файловый адаптер; `K/platform` — посредники между ViewModel и Android UI, каталогами и статусами. Существующие Java-классы с системным поведением мигрируют в файлы с теми же package/class names на переходном этапе.
- Манифест и значения ресурсов остаются в `app/src/main/`. UI-тесты запускаются через `:app:connectedDebugAndroidTest` при наличии эмулятора/устройства; отдельная сборка `:app:assembleDebugAndroidTest` не засчитывается за запуск.

---

### Task 1: Compose, темы и маршрутизация

**Файлы:** `build.gradle`, `app/build.gradle`, `K/ui/theme/AppTheme.kt`, `K/ui/navigation/AppRoute.kt`, `K/ui/navigation/AppNavHost.kt`, `app/src/main/res/{values,values-en}/strings.xml`, `I/ui/navigation/AppNavHostTest.kt`.

**Интерфейс:** `@Composable AppTheme(content: @Composable () -> Unit)`; `@Composable AppNavHost(render: @Composable (AppRoute, AppNavigator) -> Unit, modifier: Modifier = Modifier)`. `AppRoute`: `Actions`, `Settings`, `Diagnostics`, `ActionPicker`, `ShortcutPicker`, `Lockscreen`, `MenuEditor` — типизированные `@Serializable` маршруты без объектов данных. `AppNavigator.open(route: AppRoute)`, `back()`, `completeActionSelection()` (из picker и shortcut picker до `Actions`).

- [ ] Подключить плагины Kotlin `2.1.21` одной версии и `buildFeatures.compose = true`, с JVM target `1.8`, как у текущего Java. Пины для первого прохода: Compose BOM `2025.05.01`, `activity-compose:1.10.1`, `lifecycle-runtime-compose`/`lifecycle-viewmodel-compose:2.8.7`, `navigation-compose:2.8.9`, `kotlinx-serialization-json:1.8.1`, `kotlinx-coroutines-android`/`test:1.9.0`. Тесты: BOM для `ui-test-junit4`/`ui-test-manifest`, AndroidX JUnit `1.2.1` и runner `1.6.2`. Проверить resolution и minSdk; не поднимать SDK при ошибке resolution, сначала подобрать совместимый стабильный артефакт и записать замену.
- [ ] Написать `rootNavigationShowsCorrectSectionAndNeverDuplicates`, `nestedRouteHidesBarAndBackRestoresActions`, `selectionReturnsToActions` с тестовыми рендерами. Проверить старт `Actions`, один выбранный пункт панели, `Settings → Diagnostics → Back → Actions`, отсутствие нижней панели в `MenuEditor` и возврат `ActionPicker → ShortcutPicker → Actions`.
- [ ] Запустить `bash gradlew :app:assembleDebugAndroidTest`; до реализации контракта `AppNavHost` тесты не компилируются. При наличии тестового устройства запустить `:app:connectedDebugAndroidTest` после компилируемой заглушки и подтвердить отсутствие ожидаемых маршрутов.
- [ ] Реализовать `AppTheme` (light/dark, dynamic API 31+) и `AppNavHost`: `Scaffold` с `NavigationBar` только в корнях, `TopAppBar` с Back на вложенных; `launchSingleTop`, `saveState`/`restoreState`, отсутствие лишнего Back-стека. Использовать `TopAppBar`/`Scaffold`/`NavigationBar` insets без двойного padding; старый launcher пока не трогать.
- [ ] Выполнить `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` и, где доступно, `:app:connectedDebugAndroidTest`: успех сборки, три теста маршрутов проходят. Закоммитить шаг: `build: add Compose and typed navigation`.

### Task 2: Репозиторий, «Действия» и локскрин

**Файлы:** `K/data/SettingsSnapshot.kt`, `K/data/SettingsRepository.kt`, `K/data/LegacySettingsRepository.kt`, `K/platform/HandlerProbe.kt`, `K/platform/PlatformRequest.kt`, `K/actions/ActionsViewModel.kt`, `K/actions/ActionsScreen.kt`, `K/actions/LockscreenScreen.kt`, оба `strings.xml`, `U/actions/ActionsViewModelTest.kt`, `I/data/LegacySettingsRepositoryTest.kt`.

**Интерфейсы:** `SettingsRepository.observe(): Flow<SettingsSnapshot>`, `snapshot(): SettingsSnapshot`, `update(change: SettingsChange): Unit`; снимок хранит `targetId: String`, `lockscreenEnabled: Boolean`, `lockscreenActionId: String`, `vibrateEnabled: Boolean`, `vibrateDurationMs: Int`, `vibratePattern: String`, `vpnClient: String`, `menuItems: List<String>`, `triggerLog: String`. `SettingsChange` — `Target(id)`, `LockscreenEnabled(enabled)`, `LockscreenAction(id)`, `VibrationEnabled(enabled)`, `VibrationDuration(ms)`, `VibrationPattern(pattern)`, `VpnClient(client)`, `ClearLog`. `HandlerProbe.inspect(): HandlerSnapshot` — `status: HandlerStatus`, `candidates: List<HandlerCandidate>`; `HandlerStatus` — `Loading`, `ThisApp`, `OtherApp(packageName, label)`, `NoSpecificHandler`, `Unavailable`. `ActionsViewModel(repository, handlerProbe)` экспортирует `state`, `onIntent(ActionsIntent)`; экранные Composable принимают `state`, `onIntent`, `modifier`; `ActionsScreen` дополнительно получает `onOpen: (AppRoute) -> Unit`.

- [ ] Написать тесты `initialStateDoesNotWarn`, `singleResolvedSelfRequiresNoSetup`, `otherHandlerRequestsAppDetailsNotImplicitLaunch`, `noSpecificHandlerRequestsSystemResolution`, `lockscreenOffRetainsSelectedCamera` и `repositoryObservesLegacyWrite`. Последний меняет через `PrefsHelper` `special:torch → special:camera` и ждёт наблюдаемый снимок, не трогая персональный APK.
- [ ] Запустить `bash gradlew :app:testDebugUnitTest --tests '*ActionsViewModelTest'` и `:app:assembleDebugAndroidTest`; подтвердить RED на отсутствующих LCE/handler-ветках/реализации.
- [ ] Реализовать `LegacySettingsRepository(Context)` с существующими методами `PrefsHelper` и callbackFlow для изменения файла `vivo_swipe_stub_prefs`. Регистрировать listener до первого снимка, держать сильную ссылку, снимать при отмене; не дублировать ключи в UI. Иммутабельный снимок и отдельно transient UI поля.
- [ ] Реализовать `HandlerProbe` на основе текущего `MainActivity.refreshAllStates`: `MATCH_DEFAULT_ONLY`, исключить Android/resolver из конкретных обработчиков, сравнивать с `context.packageName`, возвращать отличимые состояния. Выполнять платформенные пробы вне главного потока. `PlatformRequest(id: Long, command: PlatformCommand)`; `PlatformCommand.ResolveHandler`, `OpenAppDetails(packageName)`; идентификатор одноразовой команды в UI-состоянии подтверждается хостом, потом результат возвращается в ViewModel.
- [ ] Реализовать экран «Действия» и локскрин; они используют одну ViewModel, scoped к маршруту `Actions`. Три `ListItem`, подсказка настройки только при нужном статусе; отображать локскрин «как на разблокированном» при отключении и не стирать сохранённое значение. Значения показывать доступными именами, с fallback на сохранённый ID для недоступных приложений. Пакеты пресетов не переименовывать.
- [ ] Повторить targeted JVM + Android test сборки и запустить instrumentation при наличии устройства. Проверка: другой default никогда не вызывает `ResolveHandler`, локскрин ограничен `special:torch`/`special:camera`. Коммит: `feat: model action settings and handler state`.

### Task 3: «Настройки» и «Диагностика»

**Файлы:** `K/settings/SettingsViewModel.kt`, `K/settings/SettingsScreen.kt`, `K/diagnostics/DiagnosticsViewModel.kt`, `K/diagnostics/DiagnosticsScreen.kt`, `K/platform/DeviceStatusProbe.kt`, `K/platform/PlatformRequest.kt`, оба `strings.xml`, `U/settings/SettingsViewModelTest.kt`, `U/diagnostics/DiagnosticsViewModelTest.kt`.

**Интерфейсы:** `DeviceStatusProbe.inspect(): DeviceStatus` (Happ/incy availability, VPN active, выбранный пакет ассистента); `SettingsViewModel(repository, probe)` и `DiagnosticsViewModel(repository, handlerProbe)` — `state: StateFlow<…UiState>`, `onIntent(intent: …Intent)`. Расширить `PlatformCommand`: `PreviewVibration`, `OpenAssistantSettings`, `TestAction`. Контент: `SettingsScreen`/`DiagnosticsScreen(state, onIntent, modifier)`. Экраны инициируют обновление статусов по `Resume`, не из тела композиции.

- [ ] Написать `sliderPersistsAndPreviewsOnceOnGestureEnd` (20 и 500 допустимы, промежуточный 300 не записывается), `vpnClientAbsentRetainsChoice` (`happ` остаётся), `clearLogReflectsRepository`, `testDoesNotMarkHandlerConfigured`, `resumeRefreshesDeviceAndHandlerStatus`.
- [ ] Запустить `bash gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest' --tests '*DiagnosticsViewModelTest'`; подтвердить RED.
- [ ] Реализовать оба MVI-контракта. Использовать `VpnController` и существующее определение ассистента в адаптере; Settings — `Switch` с единой семантикой строки, `Slider` 20–500 мс, варианты `single/double/long`, `auto/happ/incy`; Diagnostics — тест, настройка обработчика, список пакетов, история последнего запуска и очистка. Подробности/инструкция находятся в соответствующей группе, не на «Действиях».
- [ ] Повторить targeted JVM, `bash gradlew :app:lintDebug :app:assembleDebug`: нет новых lint errors, `TestAction` не меняет handler status. Коммит: `feat: add settings and diagnostics screens`.

### Task 4: Выбор действия, поиск приложений и Shizuku

**Файлы:** `K/platform/AppCatalog.kt`, `K/platform/ShortcutGateway.kt`, `K/platform/PlatformRequest.kt`, `K/picker/ActionPickerViewModel.kt`, `K/picker/ActionPickerScreen.kt`, `K/picker/ShortcutPickerViewModel.kt`, `K/picker/ShortcutPickerScreen.kt`, `K/ui/common/AppIcon.kt`, оба `strings.xml`, `U/picker/{ActionPickerViewModelTest,ShortcutPickerViewModelTest}.kt`.

**Интерфейсы:** `AppCatalog.apps(scope: AppScope): List<AppEntry>` и `icon(packageName: String): ImageBitmap?` — suspend; `AppScope.Installed` для существующего пикера приложений и `Launcher` для ярлыков; `AppEntry(packageName, label)`. `ShortcutGateway.status(): ShizukuShortcuts.Status`, `suspend shortcuts(packageName: String): List<ShortcutEntry>`; `ShortcutEntry(packageName, id, label, kind, rank)`. `ActionPickerViewModel(repository, catalog)` и `ShortcutPickerViewModel(repository, catalog, gateway, savedStateHandle)` — `state`/`onIntent`; `ActionPickerScreen` передаёт `onOpenShortcuts` и завершение выбора, `ShortcutPickerScreen` — завершение выбора. `AppSearchList(entries, onPicked: (String) -> Unit, modifier)` переиспользуется в редакторе. Добавить `PlatformCommand.RequestShortcutPermission`, `OpenShizuku`.

- [ ] Написать `primaryPickPersistsExistingId`, `manualPackageSurvivesCatalogRemoval` (отсутствующий `com.example.app` не теряется), `missingIconKeepsSearchResult`, `duplicatePermissionClickRequestsOnce`, `stalePermissionResultAfterExitIgnored`, `restorePendingPermissionDoesNotResubmit`, `matchesLoadedShortcutByLabelAndId`, `retryFailureThenPickKeepsCanonicalId` (включая `+`, `%`, двоеточие). Фейки управляют очередностью результатов, без `delay` в тестах.
- [ ] `bash gradlew :app:testDebugUnitTest --tests '*PickerViewModelTest'`; подтвердить RED.
- [ ] Перенести пресеты и поиск по имени/пакету из `MainActivity.showActionPicker/installedApps`. Опции Mir Pay, СБПэй, Vivo Wallet, фонарик, камера, VPN, Circle to Search, меню, ярлыки. Отдельный ручной ввод из согласованного дизайна: сохранить непустое имя пакета после обрезки внешних пробелов независимо от того, присутствует ли пакет в каталоге. `LazyColumn` со стабильными ключами; запросы Package Manager/иконок и Shizuku не на главном потоке; `CancellationException` не преобразовывать в ошибку загрузки.
- [ ] Перенести выбор ярлыков на отдельный маршрут: искать по приложениям и уже загруженным ярлыкам, раскрывать и подгружать по пакету; сохранить manifest/dynamic/pinned, сортировку rank/label/id, fallback label и иконку. В случае недоступности показать соответствующую причину и существующие повторы/открытие Shizuku. Не разрешать вторую отправку in-flight запроса; ключ поколения/запроса сверять при завершении, отписываться/отменять при закрытии. При выборе сохранять `ShortcutAction.toActionId()` и возвращаться на «Действия».
- [ ] Повторить targeted JVM и `bash gradlew :app:assembleDebug :app:lintDebug`. Коммит: `feat: migrate action and shortcut pickers to Compose`.

### Task 5: Редактор и панель меню

**Файлы:** `K/menu/MenuRules.kt`, `K/menu/MenuEditorViewModel.kt`, `K/menu/MenuEditorScreen.kt`, `K/menu/ActionSheet.kt`, `K/MenuActivity.kt`, `K/data/{SettingsRepository,LegacySettingsRepository}.kt`, `K/platform/PlatformRequest.kt`, удалить `J/MenuActivity.java`; оба `strings.xml`; `U/menu/{MenuRulesTest,MenuEditorViewModelTest}.kt`, `I/menu/ActionSheetTest.kt`.

**Интерфейсы:** `visibleMenuItems(raw: List<String>): List<String>`; `MenuEdit.Add(id)`, `RemoveAt(index)`, `Move(from, to)`; `MenuEditResult.Applied`, `Duplicate`, `Full`, `NoChange`; `SettingsRepository.editMenu(edit: MenuEdit): MenuEditResult` выполняет чтение/проверку/запись под одним lock. `MenuEditorViewModel(repository, catalog)` — `state`/`onIntent`; `MenuEditorScreen(state, onIntent, modifier)`; `ActionSheet(items: List<MenuItemUi>, onSelected: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier)`; `PlatformCommand.PreviewMenu`.

- [ ] Написать `visibleMenuDropsRecursiveDuplicatesAndStopsAt16`, `addDuplicateAndSeventeenthLeaveListUnchanged`, `removeFinalItemRetainsEmptyMenu`, `movePersistsOrder`, `editorExpandsSearchableAppPicker`; UI `emptyMenuAndBackDismiss` и `doubleTapDispatchesOnce`; тест последовательности для Circle to Search: `close → finish → dispatch` через управляемый фейковый clock/хост.
- [ ] `bash gradlew :app:testDebugUnitTest --tests '*Menu*Test'` и `:app:assembleDebugAndroidTest`; подтвердить RED.
- [ ] Реализовать редактор с немедленным сохранением, поиском при добавлении приложения (`app:<package>`), 4 поддерживаемыми спец-действиями, кнопками вверх/вниз/удалить с описаниями и предпросмотром. Сырые индексы редактора не путать с отфильтрованными индексами панели; в сохранённых данных не пересортировывать и не переписывать без явной правки.
- [ ] Заменить `J/MenuActivity.java` на Activity с тем же именем, task/window параметрами и Compose-панелью с нижней анимацией и scrim в **том же окне**. Не делать второй Activity/Dialog Window для sheet, если он ломает показ на lockscreen. Настоящая панель одна для кнопки и предпросмотра; фонарик/камера/VPN/приложения выполняются немедленно через `ActionRunner`, а `special:circle_to_search` — после скрытия окна, с существующей задержкой 550 мс и application context. Back/тап вне панели закрывают без запуска; первый тап по действию блокирует повторы.
- [ ] Повторить targeted JVM + `bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; с доступным устройством выполнить `ActionSheetTest`, а поверх блокировки — физический smoke test. Коммит: `feat: migrate action menu and editor to Compose`.

### Task 6: Переключение launcher на Compose

**Файлы:** `K/MainActivity.kt`, `K/AppDependencies.kt`, `K/platform/PlatformRequestHost.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/{values,values-night}/styles.xml`, `I/ComposeAppIntegrationTest.kt`; удалить `J/{MainActivity,MenuEditorActivity,ShortcutPickerDialog,FlowLayout,Ui,SystemBarInsets}.java`.

**Интерфейсы:** `AppDependencies(context: Context)` создаёт один `SettingsRepository` для UI и его фабрики ViewModel, а также probe/catalog/gateway. `PlatformRequestHost.execute(request: PlatformRequest)` маршалит результат/подтверждение в инициировавший сценарий; `close()` снимает Activity-bound callbacks. Launcher `MainActivity : ComponentActivity` остаётся в исходном пакете.

- [ ] Написать `launchShowsActionsWithoutLegacyHints`, `navigatePickReturnAndSeeCommittedAction`, `settingsDiagnosticsAreReachableWithoutBackStackDuplicates`, `systemPermissionDeliveryNotRepeatedOnActivityRecreation` (подставной хост, не настоящий запрос Shizuku). Тестовый выбор сохраняет и восстанавливает настройку собственной debug-сборки.
- [ ] Собрать/запустить `ComposeAppIntegrationTest` через `bash gradlew :app:assembleDebugAndroidTest` и `:app:connectedDebugAndroidTest` при доступном устройстве; красный результат, когда launcher ещё Java, или тестовый контракт хоста не подключён.
- [ ] Реализовать композицию: `setContent`, системные insets, `collectAsStateWithLifecycle`, ViewModel на соответствующие маршруты, `SavedStateHandle` для поиска/раскрытия, resume hooks. Системные команды исполняет Activity; прежние способы запуска берём из `MainActivity`: неявный Vivo Intent, сведения о пакете, список fallback Intent ассистента, прямой `SwipeEntryActivity` для теста, `MenuActivity` для предпросмотра, Shizuku listener. Подтверждать принятие effect до внешнего запуска, ошибки отображать в UI, отписываться при завершении Activity. Открытие системного UI по явному намерению, не из reducer.
- [ ] Удалить прежние UI-классы и только manifest declaration `MenuEditorActivity`; сохранить запись launcher, все параметры `SwipeEntryActivity` и `MenuActivity`, provider, queries и permissions. Удалять legacy-ресурсы только после проверки ссылок из ещё Java-слоя, не удалять используемые локализации. Доступность: не дублировать речь иконкой, проверить заголовки секций, большие системные шрифты и жестовый Back.
- [ ] `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; проверить merged manifest; запустить интеграционные тесты на доступном устройстве. Коммит: `feat: switch launcher to Material 3 Compose`.

### Task 7: Совместимые настройки и кодек на Kotlin

**Файлы:** `K/PrefsHelper.kt`, `K/ShortcutAction.kt`, `K/data/LegacySettingsRepository.kt` → `K/data/SharedPrefsSettingsRepository.kt`, `K/AppDependencies.kt`, удалить `J/{PrefsHelper,ShortcutAction}.java`; перенести `app/src/test/java/com/vivotomirpay/stub/ShortcutActionTest.java` в `U/ShortcutActionTest.kt`; `I/data/LegacySettingsRepositoryTest.kt` → `I/data/SettingsCompatibilityTest.kt`.

**Интерфейсы:** контракты `SettingsRepository`/`SettingsSnapshot` неизменны. Сохранить сигнатуры `PrefsHelper` для оставшихся Java-вызовов через `@JvmStatic`/`const val`; `ShortcutAction(packageName: String?, shortcutId: String?, label: String?)` с non-null свойствами, `parse(actionId: String?): ShortcutAction?`, `isAction(actionId: String?): Boolean`, `toActionId(): String`; companion методы доступны Java.

- [ ] Расширить исходные 11 тестов кодека и выполнить их на Java как characterization. В `encodedSpacesAreNotTrimmedFromIdentity` проверить, что `shortcut:+com.example.app+:%C2%A0:a%2Bb%25%3A` сохраняет `" com.example.app "`, ID `\u00a0`, label `a+b%:` и возвращает идентичный wire ID. `legacyPayloadReadsWithoutReset`: target `shortcut:com.example.app:a%2Bb:`, menu `[]`, вибрация 40, `vpn_client=incy`, `torch_on=true`, `double_press_package` и журнал. `missingOrBadMenuGetsOriginalFourDefaults`: `[special:torch, special:camera, special:vpn, special:circle_to_search]`. `longLogRetainsOnlyBoundedText`: 100 символов на значение extras, весь лог ≤1000. Инструментальные тесты создают данные через старые ключи, не через новый адаптер.
- [ ] Проверить RED нового API/теста возврата снимка на отсутствующей реализации Kotlin, при этом characterization предыдущего формата должен оставаться зелёным. `bash gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest`.
- [ ] Перенести Prefs и кодек механически. Не нормализовать Unicode/пробелы ID новым правилом: для legacy `String.trim()` повторить границу `<= U+0020`; `URLDecoder`/`URLEncoder` UTF-8 с сохранением `+` и пустого последнего поля. Единое имя файла и ключи в `PrefsHelper`; репозиторий наблюдает этот же файл. Сохранить escapes, fallback, порядок и установку значений, диапазон и длины журнала. Не изменять данные на чтении.
- [ ] `bash gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`; проверить legacy fixture и старый Java-вызов `ActionRunner` в `assembleDebug` до следующей миграции; instrumentation при наличии устройства. Коммит: `refactor: port settings and shortcut ids to Kotlin`.

### Task 8: Перенос платформенных адаптеров без изменения поведения

**Файлы:** `K/{VpnController,ShizukuShortcuts,HiddenApiCompat,Vibe}.kt`; удалить соответствующие четыре `J/*.java`; `U/platform/VibrationWaveformTest.kt`, `I/platform/PlatformAdaptersTest.kt`.

**Интерфейсы:** сохранить Java-совместимость оставшегося `ActionRunner.java` и `SwipeEntryActivity.java` через `@JvmStatic`/`const val`. Сохранить `VpnController.controlVpn(context, clientSetting, mode): String`, `isAnyVpnActive(context): Boolean`, `ShizukuShortcuts.checkStatus(): Status`, `getShortcuts`, `getShortcutIcon`, `startShortcut`, `HiddenApiCompat.ensureExemptions`, `showSessionFromSession`, `Vibe.trigger(context)`. Изолировать для теста `vibrationWaveform(durationMs: Int, pattern: String): LongArray`.

- [ ] Написать `usesLegacyVibrationWaveforms` (single 40 → `[0,40]`, double 40 → `[0,40,60,40]`, long 500 → `[0,600]`), `unsupportedAndroidShortCircuitsShizukuBeforeBinder` на API ≤29, `missingVpnClientReturnsDiagnosticInsteadOfStartingActivity` на устройстве без клиентов. Внешние клиентские пути сверять с текущим `VpnController`, не менять их на тестовые догадки.
- [ ] Запустить `bash gradlew :app:testDebugUnitTest --tests '*VibrationWaveformTest'`; подтвердить RED нового изолированного входа.
- [ ] Перенести классы; сохранить проверки API, UID, разрешений, ошибки Binder/Linkage и порядок платформенных вызовов. API-зависимые обращения оставить за теми же версиями Android. Провести пометодное сравнение Kotlin/Java до удаления исходников.
- [ ] `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; выполнить доступные тесты Android, ручные Happ/incy и Shizuku оставить в матрице задачи 10. Коммит: `refactor: port platform adapters to Kotlin`.

### Task 9: Порт запуска действий и входа Vivo

**Файлы:** `K/ActionRunner.kt`, `K/SwipeEntryActivity.kt`, `K/platform/SwipeActionSelection.kt`, удалить `J/{ActionRunner,SwipeEntryActivity}.java`; `U/platform/SwipeActionSelectionTest.kt`, `I/platform/SwipeEntryContractTest.kt`.

**Интерфейсы:** `resolveSwipeAction(locked: Boolean, lockscreenEnabled: Boolean, lockscreenActionId: String, targetId: String): String`; `ActionRunner.runAction(context: Context, actionId: String?, sourceIntent: Intent?): String`; `SwipeEntryActivity.ACTION_VIVO_SWIPE` и имя компонента остаются. Никаких Compose/Flow в `SwipeEntryActivity`.

- [ ] Написать `unlockedAlwaysUsesPrimary`, `lockedWithOptOutUsesPrimary`, `lockedCameraPreserved`, `lockedInvalidUsesTorch`; `manifestResolvesExactVivoComponent` проверяет ID/экспорт/фильтр `NFC_BUS_SWINGSPLASH`/`DEFAULT`, обе группы метаданных Vivo и параметры окна, отдельно отсутствие новых permissions. `normalizationPreservesLegacyActionDispatch` проверяет `null`, ASCII whitespace, `app:`, `special:` и `shortcut:` на границе переноса.
- [ ] `bash gradlew :app:testDebugUnitTest --tests '*SwipeActionSelectionTest' :app:assembleDebugAndroidTest`; подтвердить RED нового правила, старый манифест тестируется как characterization.
- [ ] Перенести общий исполнитель и системную Activity с прежними ветками, флагами Intent, исключениями, статусами и границами API; сохранить сценарии `onCreate/onNewIntent`, вибрацию, камеру и задержку завершения 450 мс. Не расширять работоспособность скрытых вызовов или доступов в рамках переноса. Проверить обе точки вызова (`MenuActivity` и `SwipeEntryActivity`) и отсутствие зависимости от `MainActivity`.
- [ ] `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; запустить контрактный Android-тест, сверить манифест и пометодное соответствие. Коммит: `refactor: port Vivo action entry and runner to Kotlin`.

### Task 10: Совместимость обновления, полный прогон и документация

**Файлы:** `README.md`, при необходимости `docs/superpowers/plans/2026-11-25-kotlin-compose-mvi-verification.md` для матрицы/фактических результатов; не редактировать пользовательские APK.

**Интерфейсы:** итоговые `app/build/outputs/apk/{debug/app-debug.apk,release/app-release.apk}`; вывод Gradle/AndroidTests и список реально проверенных устройств и сценариев.

- [ ] Проверить финальный production tree: `find app/src/main -name '*.java' -print` пуст, README описывает три раздела и актуальные пути; базовые XML темы `TransparentSwipeTheme` и `MenuTheme` остались. Проверить все строки новых экранов в RU/EN и ссылки README, не оставлять ручную палитру `Ui`.
- [ ] Выполнить `bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`. Требование: `BUILD SUCCESSFUL`, ноль тестовых ошибок, APK собираются; записать оба размера release в байтах и дельту при неизменной конфигурации.
- [ ] Выполнить `bash gradlew :app:connectedDebugAndroidTest` на доступных API 25 и 31+ либо явно записать, какие из них отсутствуют. Проверить large font, RU/EN, TalkBack, светлую/тёмную/динамическую палитру, IME и системные панели.
- [ ] Проверить обновление на чистом тестовом профиле: исходный debug/release с той же подписью и ID → итоговый APK поверх, без uninstall. Сравнить целевое приложение, ярлык, оба варианта локскрина, вибрацию, VPN, порядок, пустое меню и журнал. Начальный APK из baseline, `adb install -r` только для заранее выбранного тестового устройства. Без устройства — отметить «не проверено», не обещать совместимость на реальном телефоне.
- [ ] Проверить физическую кнопку Vivo/iQOO: состояние выбранного обработчика после обновления, разблокированный/заблокированный экран, холодный/повторный запуск, камера, фонарик, меню (включая Back и CTS после закрытия), оба VPN-клиента по доступности, ярлык с Shizuku shell и без сервиса/разрешения, Circle to Search на поддерживаемой конфигурации. Не подменять этот прогон кнопкой «Проверить» внутри приложения.
- [ ] Обновить README (UI, карта Kotlin-пакетов, `bash gradlew`, честный размер APK) и записать ограничения проверки. Проверить `git diff --check`, сверить полный diff и этапы с дизайном, закоммитить документацию: `docs: describe Compose UI and migration verification`. Не публиковать APK/ветку без отдельной просьбы.
