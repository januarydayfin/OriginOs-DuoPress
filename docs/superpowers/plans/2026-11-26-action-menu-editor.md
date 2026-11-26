# Редактор «Меню действий» — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавлять по одному пункту через «+», менять порядок перетаскиванием и открывать рабочее меню из тулбара.

**Architecture:** `SettingsRepository.editMenu` остаётся единственной точкой записи меню. У редактора одно состояние добавления; подборщик ярлыков получает назначение выбора из своего маршрута. Тулбар и предпросмотр используют ту же модель редактора, что и список.

**Tech Stack:** Kotlin, Compose Material 3, Navigation Compose, StateFlow, SharedPreferences, существующий шлюз Shizuku; образец перетаскивания — `sh.calvin.reorderable:reorderable:3.0.0` из Bargen.

**Spec:** [Утверждённый дизайн](../specs/2026-11-26-action-menu-editor-design.md)

## Global Constraints

- Лимит — **16 позиций** исходного массива. Старые данные не мигрировать и не очищать; редактировать по позиции.
- Успешный выбор добавляет **один** пункт в конец. Ошибка не закрывает выбор. «Назад» из обоих подборщиков возвращает к сетке без записи.
- Формат `ShortcutAction` и основное назначение кнопки телефона сохраняются. Рабочая панель и предпросмотр — одна и та же `MenuActivity`.
- Сохранить `minSdk 25`, тему приложения и обе локали (`values`, `values-en`). Перед изменением символов — upstream impact GitNexus и сообщение о радиусе влияния; перед каждым коммитом — `detect_changes(scope="staged")`.

## Review Focus

1. Ярлык переименован или имеет пустую подпись: идентичность определяется пакетом и ID, в панели нет сериализованной строки (задача 1).
2. Разрешение Shizuku не выдано или результат пришёл после выхода: ничего не добавляется и основной выбор не меняется (задача 2).
3. Поворот, двойной тап и «Назад» не должны дважды добавить ярлык, показать две сетки или увести из редактора на корневой экран (задача 3).
4. На границе 15/16 и при ошибке записи состояние FAB, плиток и списка соответствует сохранённым данным (задача 3).
5. Перетаскивание второй одинаковой строки, отмена жеста или внешнее изменение списка во время жеста не должны переместить соседнюю строку (задача 4).

---

**Карта файлов.** Ниже `K` = `app/src/main/kotlin/com/krayapp/duopress`, `U` и `I` — тот же пакет в `app/src/test/kotlin` и `app/src/androidTest/kotlin`. `menu/` владеет списком и правилами редактирования, `picker/` — каталогом и выбором, `ui/navigation/` и `MainActivity.kt` — маршрутами и командами хоста. `MenuActivity.kt` строит рабочую панель. Все команды — из корня; `connectedDebugAndroidTest` требует устройство или эмулятор.

### Task 1: Идентичность и отображение ярлыка

**Файлы:** `K/menu/MenuRules.kt`, `K/MenuActivity.kt`, `K/menu/MenuEditorScreen.kt`, `U/menu/MenuRulesTest.kt`.

**Интерфейс:** в `MenuRules.kt` добавить `internal fun sameMenuItem(a: String, b: String): Boolean` и `internal fun menuShortcutLabel(id: String): String?`. Для двух корректных ярлыков сравнивать `(packageName, shortcutId)`; для остальных ID — существующий `legacyMenuTrim()`. Подпись — `label`, при пустой подписи — `shortcutId`, для неярлыка — `null`.

- [ ] Написать тест `renamedShortcutIsDuplicate`: два `ShortcutAction("pkg","home", ...)` с разными подписями → `assertEquals(MenuEditResult.Duplicate to raw, edit(raw, MenuEdit.Add(renamed)))`; тот же ID в другом пакете → `Applied`. В `blankShortcutLabelUsesId` проверить `assertEquals("home", menuShortcutLabel(ShortcutAction("pkg","home","").toActionId()))`.
- [ ] Запустить `./gradlew :app:testDebugUnitTest --tests '*MenuRulesTest'`; увидеть отказ новой проверки.
- [ ] Использовать `sameMenuItem` в проверке `MenuEdit.Add`, не переписывая старые записи. Использовать `menuShortcutLabel` в редакторе и `loadMenuItems`; передавать в `MenuSelection` исходный ID. Запуск уже реализован в `ActionRunner`.
- [ ] Повторить тест: PASS, включая существующие случаи `Move`, предела и старых ID. Коммит `feat: поддержать ярлыки в элементах меню`.

### Task 2: Два назначения одного подборщика ярлыков

**Файлы:** `K/picker/ShortcutPickerViewModel.kt`, `K/picker/ShortcutPickerScreen.kt`, `U/picker/ShortcutPickerViewModelTest.kt`. Использовать существующие `SettingsRepository` и `ShortcutGateway`.

**Интерфейс:** `ShortcutPickTarget { MainAction, MenuItem }`; добавить в конец конструктора модели `target: ShortcutPickTarget = ShortcutPickTarget.MainAction`. Для меню состояние предоставляет `menuItems: List<String>` и `menuEditResult: MenuEditResult?`; UI сверяет строку через `sameMenuItem`. Существующие `selectionComplete` и `claimSelectionCompletion` сохраняют смысл «запись прошла».

- [ ] Добавить `menuPickAppendsWithoutChangingTarget`: после выбора проверить `assertEquals(listOf(action.toActionId()), repository.snapshot().menuItems)` и `assertEquals(originalTarget, repository.snapshot().targetId)`. Для `Duplicate`, `Full`, исключения записи и отказа в доступе проверить `assertFalse(model.state.value.selectionComplete)` и неизменность данных. В тесте позднего результата вызвать `Exit` до callback разрешения; ни одной записи.
- [ ] `./gradlew :app:testDebugUnitTest --tests '*ShortcutPickerViewModelTest'` — новые сценарии сначала красные.
- [ ] В меню писать `repository.editMenu(MenuEdit.Add(action.toActionId()))` на существующем диспетчере; завершать только при `Applied`. Пометить недоступные строки «Уже в меню», показать причину отказа. В режиме `MainAction` сохранить `SettingsChange.Target`, текущую подсветку и проверку разрешений. Обновить строки обеих локалей в этой задаче, если для обратной связи нет подходящего ресурса.
- [ ] Повторить класс тестов: обе ветви зелёные. Коммит `feat: добавить режим выбора ярлыка для меню`.

### Task 3: FAB, сетка, навигация и предпросмотр

**Файлы:** `K/menu/MenuEditor{Screen,ViewModel}.kt`, новый `K/menu/MenuAddDialog.kt`, `K/picker/ActionPickerScreen.kt`, `K/ui/navigation/{AppRoute,AppNavHost}.kt`, `K/{AppDependencies,MainActivity}.kt`, `app/src/main/res/{values,values-en}/strings.xml`. Тесты: `U/menu/MenuEditorViewModelTest.kt`, `I/ComposeAppIntegrationTest.kt`, `I/ui/navigation/AppNavHostTest.kt`.

**Интерфейсы:** `MenuPicker { None, Grid, Apps }`; `OpenGrid`, `OpenApps`, `BackFromPicker`, `FinishMenuAddition` — события `MenuEditorIntent`. Новый сериализуемый маршрут `AppRoute.MenuShortcutPicker` открывает модель с `target = MenuItem` через фабрику для этой записи стека. У `AppNavHost` добавить слот `menuEditorActions: @Composable (NavBackStackEntry) -> Unit = {}` только для тулбара редактора; сохранить совместимость вызовов с `render`. У списка приложений — предикат для недоступных пакетов с нейтральным значением по умолчанию.

- [ ] В тестах модели проверить переходы `None → Grid → Apps → Grid → None`, ошибку сохранения с открытым подборщиком, `15 → 16 → удалить → 15`. Обновить интеграционный тест старых трёх кнопок: открыть глаз без прокрутки; открыть «+», проверить шесть плиток, добавить камеру, открыть сетку ещё раз и проверить недоступность «Камеры». Для ярлыков проверить отмену в сетку и успешный возврат непосредственно в редактор после поворота; два быстрых нажатия дают одну запись, `targetId` прежний.
- [ ] Запустить `./gradlew :app:testDebugUnitTest --tests '*MenuEditorViewModelTest'` и `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.krayapp.duopress.ComposeAppIntegrationTest`; зафиксировать новые отказы.
- [ ] Реализовать закреплённый FAB, пустое состояние, фактический счётчик из 16 и объяснение лимита; оставить нижний отступ для последней строки. `MenuAddDialog` показывает плитки в порядке спецификации. Диалог приложений сохраняет текущие поиск, загрузку и повторную попытку. Пока идёт запись, повторный выбор не принимается.
- [ ] При открытии ярлыков оставить родительский редактор в `Grid`; окно сетки показывать только для текущего редактора, не поверх дочернего маршрута. При обычном Back дочерняя запись снимается и сетка видна снова. При успешной записи только активная запись подборщика однократно вызывает `FinishMenuAddition` у модели редактора **до** возврата на один уровень. Не вызывать `completeActionSelection`, который снимает стек до `Actions`. Подключить к новому маршруту существующие обработку Shizuku и отзыв запросов при выходе. Глаз отправляет `Preview` той же модели редактора через существующий хост.
- [ ] Повторить оба запуска, включая проверку прежнего основного выбора; `AppNavHostTest` дополнить обработкой нового маршрута. Коммит `feat: переработать добавление и предпросмотр меню`.

### Task 4: Перетаскивание и доступное изменение порядка

**Файлы:** `app/build.gradle`, `K/menu/MenuEditorScreen.kt`, новый `K/menu/ReorderableMenuList.kt`, ресурсы обеих локалей; новый `I/menu/MenuEditorReorderTest.kt`, существующие `U/menu/MenuRulesTest.kt`, `I/menu/MenuPersistenceTest.kt`.

**Интерфейс:** `@Composable internal fun ReorderableMenuList(items: List<String>, enabled: Boolean, onMove: (Int, Int, List<String>) -> Unit, modifier: Modifier = Modifier, row: @Composable (rawIndex: Int, handle: Modifier) -> Unit)`. Редактор рисует строку по исходной позиции и ставит `handle` на ручку. Контракт записи уже есть: `MenuEditorIntent.Move(from, to, expectedRaw)`.

- [ ] В Compose-тесте использовать сырой список `["app:a","special:camera","app:a","app:b"]`. Перетащить третью строку на второе место: `assertEquals(Triple(2,1,original), moves.single())`. При отмене или внешней смене списка до отпускания — `assertTrue(moves.isEmpty())`. Проверить, что семантическое действие «Выше» у средней строки даёт ту же команду, а у первой отсутствует.
- [ ] Запустить `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.krayapp.duopress.menu.MenuEditorReorderTest` — получить отказ до реализации.
- [ ] Подключить зависимость из Bargen. Зафиксировать исходный массив и уникальные ключи **позиций** на начало жеста; переставлять строки локально, записывать один `Move` при успешном отпускании. Отмена, смена снимка и неуспешная запись возвращают сохранённый порядок. Индекс заголовка не входит в индексы данных. Ручка справа, корзина перед ней; жест за пределами ручки прокручивает список. TalkBack-команды используют тот же `Move`.
- [ ] Повторить тест жестов, затем `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` и `./gradlew :app:connectedDebugAndroidTest` — `BUILD SUCCESSFUL`. На устройстве с Shizuku пройти путь «добавить ярлык → переместить → глаз → запустить → открыть редактор снова»; подтвердить запуск нужного ярлыка, порядок и прежнее назначение кнопки. Если устройства нет, отметить непроверенный сценарий отдельно.
- [ ] Коммит `feat: перемещать пункты меню за ручку`.
