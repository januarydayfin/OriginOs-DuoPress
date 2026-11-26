# Экран приложений и тактильная сортировка — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перенести подбор приложения из диалога на экран и дать почувствовать захват и перестановку строки.

**Architecture:** Новый дочерний маршрут использует модель родительского редактора и уже существующие операции поиска и атомарного добавления. Отклик живёт в UI списка, где распознаётся жест и меняется локальный порядок.

**Tech Stack:** Kotlin, Compose Material 3, Navigation Compose, `sh.calvin.reorderable`.

**Spec:** [Утверждённое изменение](../specs/2026-11-26-menu-picker-and-haptics-design.md).

## Global Constraints

- Текущий worktree `feat/action-menu-editor`; сохранить лимит 16, `minSdk 25`, русские и английские ресурсы.
- Успех возвращает в редактор, отмена — к сетке. Ошибка оставляет подбор открытым.
- Только системный отклик касаний; не использовать вибрацию запуска действия или обход системных настроек.

## Review Focus

1. Обе кнопки «Назад» из списка возвращают к сетке; с открытой клавиатурой системный Back сначала скрывает её.
2. Двойной тап или Back во время записи не добавляют два пункта и не возвращают сетку после успеха.
3. Восстановленный маршрут использует ту же модель редактора; заголовок и список доступны после пересоздания Activity.
4. Касание ручки без перетаскивания, прокрутка вне ручки и отсутствие смены позиции не создают щелчков перестановки.
5. После отмены или выключения системного отклика нет лишнего импульса; отменённый жест не сохраняется.

---

Ниже `K` — `app/src/main/kotlin/com/krayapp/duopress`, `I` — `app/src/androidTest/kotlin/com/krayapp/duopress`. До правок — impact изменяемых символов; перед коммитами — проверка staged diff в GitNexus.

### Task 1: Полноэкранный выбор приложения

**Файлы:** `K/ui/navigation/{AppRoute,AppNavHost}.kt`, `K/MainActivity.kt`, `K/menu/{MenuAddDialog,MenuEditorScreen}.kt`, новый `K/menu/MenuAppPickerScreen.kt`, `I/{ComposeAppIntegrationTest,ui/navigation/AppNavHostTest}.kt`.

**Интерфейс:** `AppRoute.MenuAppPicker`, `MenuAppPickerScreen(state: MenuEditorState, onIntent: (MenuEditorIntent) -> Unit, loadIcon: suspend (String) -> ImageBitmap?)`. Использовать существующие `OpenApps`, `SearchApps`, `PickApp`, `BackFromPicker`.

- [ ] Дополнить интеграционные тесты: `«+» → Приложения` показывает заголовок в тулбаре и поиск вне диалога. Проверить Back и поворот; при выборе ожидается `menuItems == before + "app:$pkg"`, прежний `targetId` и отсутствие сетки. Запустить класс `ComposeAppIntegrationTest` на эмуляторе, убедиться, что прежний диалог не проходит новую проверку.
- [ ] Вынести содержимое ветки `Apps` из `MenuAddDialog` в экран. Открывать маршрут один раз по нажатию плитки; владение каталогом, запросом и записью оставить у модели `MenuEditor`. Обрабатывать Back из дочернего маршрута и однократное снятие маршрута после успешной записи, различая `Grid` и `None`.
- [ ] Повторить тесты навигации и интеграции; при зелёном результате закоммитить `feat: открывать выбор приложения на отдельном экране`.

### Task 2: Отклик ручки и смены позиции

**Файлы:** `K/menu/ReorderableMenuList.kt`, `I/menu/MenuEditorReorderTest.kt`.

**Интерфейс:** сохранить контракт `onMove(from, to, expectedRaw)`; для отправки сигналов использовать системный хаптик из composable. Переупорядочивание TalkBack не имитирует захват ручки.

- [ ] Расширить тест жеста с записью событий отклика. После касания — пусто; после захвата и одного перехода — `[захват, щелчок]`; после отпускания или отмены список событий не увеличивается. Запустить класс `MenuEditorReorderTest` и увидеть отказ по отсутствующему отклику.
- [ ] Посылать сигнал захвата из `onDragStarted`, а сигнал смены позиции — только когда callback перестановки действительно меняет порядок активного жеста. Не добавлять сигнал в `onMove`, который вызывается при сохранении.
- [ ] Запустить `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest` на эмуляторе. При успехе закоммитить `feat: добавить тактильный отклик при сортировке`. Обновить debug-сборку на тестовом CPH2487 для проверки ощущений с включённой и выключенной системной вибрацией касаний.
