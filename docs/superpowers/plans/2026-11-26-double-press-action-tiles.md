# Double-Press Action Tiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить список действий двойного нажатия плитками и вынести выбор приложения на отдельный экран.

**Architecture:** Вынести визуальный компонент плитки в `ui/common` и использовать общий список приложений с опциональным выделением пакета. Новый маршрут использует `ActionPickerViewModel` родительского экрана, а хост завершает выбор только из активного маршрута.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation Compose, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-11-26-double-press-action-tiles-design.md`

## Global Constraints

- Без новых зависимостей, изменений формата настроек и видимых изменений редактора меню.
- Используем существующие локализованные подписи, модель выбора и каталог приложений.
- До правок существующих символов выполнить GitNexus impact; перед коммитом, если он согласован, выполнить detect_changes.

## Review Focus

- Уход из приложений без выбора не трогает сохранённое действие и черновик ручного ввода: `doublePressAppsBackKeepsCurrentActionAndManualDraft`.
- Пересоздание Activity на экране приложений сохраняет экран, запрос и выделение текущего приложения: `doublePressAppPickerRestoresSearchAndSelection`.
- Двойной клик и «Назад» в том же кадре после начала записи не приводят к повторному выбору или застреванию на плитках: `doublePressAppSelectionReturnsToActionsOnce`.
- Устаревший клик по плитке после начала записи встроенного действия не открывает приложения: `staleAppsClickCannotNavigateInTheSameTurnAsActionSave`.
- Пробелы вокруг вручную введённого пакета не меняют формат сохранения: `manualDoublePressPackageRemainsOnTileScreen`.

---

### Task 1: Плитки и отдельный маршрут приложений

**Files:**
- Create: `app/src/main/kotlin/com/krayapp/duopress/ui/common/ActionTile.kt`
- Create: `app/src/main/kotlin/com/krayapp/duopress/picker/DoublePressAppPickerScreen.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/menu/MenuAddDialog.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/picker/ActionPickerScreen.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/ui/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/MainActivity.kt`
- Test: `app/src/androidTest/kotlin/com/krayapp/duopress/ComposeAppIntegrationTest.kt`
- Test: `app/src/androidTest/kotlin/com/krayapp/duopress/ui/navigation/AppNavHostTest.kt` (добавить ветку нового маршрута в тестовый рендерер)

**Interfaces:**
- Consumes: `ActionPickerViewModel.state`, `onIntent`, `icon`, `claimSelectionCompletion`; `PickerSearchField`, `PickerMessage`, `PickerChoice`.
- Produces: `ActionTile(label: Int, icon: Int, modifier: Modifier, enabled: Boolean, alreadyAdded: Boolean = false, opensPicker: Boolean = false, selected: Boolean = false, onClick: () -> Unit)`, `DoublePressAppPickerScreen(viewModel: ActionPickerViewModel, modifier: Modifier = Modifier)`, `AppRoute.DoublePressAppPicker`, `AppNavigator.actionPickerEntry(): NavBackStackEntry`. `ActionPickerScreen` получает обязательный `onOpenApps: () -> Unit`; `AppSearchList` — завершающий аргумент `selectedPackage: String? = null`.

- [ ] **Step 1: Write the failing tests.** Добавить в `ComposeAppIntegrationTest`, используя существующие `launch`, `openActionPicker`, `clickBack` и `assertActions`:
  - `doublePressAppsBackKeepsCurrentActionAndManualDraft`: при сохранённом `special:camera` камера выделена; поиск отсутствует на экране плиток; ручное поле содержит введённый черновик после «Назад» с экрана `picker_section_apps`; настройка равна `special:camera`.
  - `doublePressAppPickerRestoresSearchAndSelection`: при сохранённом `com.android.settings` плитка приложений выделена; ввести `com.android.settings` в поиск (строка результата исключает `hasSetTextAction()`), выбрать узел результата через `assertIsSelected()` до и после `scenario.recreate()`, проверить заголовок приложений.
  - `doublePressAppSelectionReturnsToActionsOnce`: при сохранённом `special:camera` найти `com.android.settings` по пакету, вызвать два обработчика выбора и «Назад» в одном `runOnIdle`, проверить раздел «Действия», отсутствие вложенного заголовка и сохранённое `com.android.settings`.
  - `staleAppsClickCannotNavigateInTheSameTurnAsActionSave`: взять callbacks фонарика и приложений из UI в текущем кадре, вызвать по очереди, дождаться `special:torch` и проверить раздел «Действия» без вложенного заголовка приложений.
  - `manualDoublePressPackageRemainsOnTileScreen`: заполнить поле значением `"  org.example.manual  "`, подтвердить кнопкой `picker_manual_confirm` и проверить переход к «Действиям» с сохранённым `"org.example.manual"`.
- [ ] **Step 2: Verify red.** Запустить `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.krayapp.duopress.ComposeAppIntegrationTest` с устройством: тесты навигации приложений падают, поскольку текущий `picker_section_apps` — заголовок встроенного списка, а не плитка.
- [ ] **Step 3: Extract the shared `ActionTile`.** Сохранить оформление редактора и режим `alreadyAdded`. Добавить визуальный признак и `selected`-семантику для текущего действия; стрелка остаётся только на плитках перехода.
- [ ] **Step 4: Turn `ActionPickerScreen` into a scrollable two-column tile screen.** Поставить «Приложения» и «Ярлыки» в первый ряд, остальные плитки построить из `presetRows`; дать неполному ряду пустую вторую колонку. Оставить ручной ввод, ошибку сохранения и запрет параллельного выбора; не показывать поиск и список приложений здесь.
- [ ] **Step 5: Build `DoublePressAppPickerScreen`.** Подключить прежние `Search`, `ReloadApps` и `PickApp(packageName, state.query)`, состояния загрузки, фильтрации, ошибки и пустого результата. Показать ошибку записи отдельно от ошибки загрузки. Использовать `selectedPackage` в `AppSearchList`, сохранив поведение меню по умолчанию.
- [ ] **Step 6: Wire the route.** Зарегистрировать `DoublePressAppPicker` с `picker_section_apps`, использовать ViewModel родительской записи, открывать приложения только с активной записи вне `Saving`/`Complete`. При обычном Back из дочернего маршрута не закрывать ViewModel родителя; при `Saving`/`Complete` дать дочернему маршруту завершить запись. На успех использовать `CompleteSelection` с дочерней активной записью и существующим выходом до `AppRoute.Actions`. Добавить ветку маршрута в `AppNavHostTest.TestScreen`.
- [ ] **Step 7: Verify green.** Запустить `./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest`, затем `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.krayapp.duopress.ComposeAppIntegrationTest,com.krayapp.duopress.ui.navigation.AppNavHostTest`. Ожидается `BUILD SUCCESSFUL` в обеих командах; без устройства отдельно отметить невозможность запуска инструментальных тестов.
- [ ] **Step 8: Review and prepare the diff.** Проверить `git diff --check` и регрессии редактора, ярлыков и ручного ввода; при согласованном коммите использовать `feat: добавить плитки выбора действия двойного нажатия`.
