# Compatibility Dialogs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить стартовое предупреждение для других вендоров и объяснение отсутствия Shizuku при нажатии «Ярлыки» в обеих настройках.

**Architecture:** Стартовое предупреждение живёт поверх навигации главного окна. Наличие Shizuku проверяется отдельно от состояния сервиса; один компонент оборачивает общую плитку «Ярлыки» в обоих сценариях.

**Tech Stack:** Kotlin, Android PackageManager, SharedPreferences, Compose Material 3, JUnit, Compose UI Test.

**Spec:** `docs/superpowers/specs/2026-11-26-compatibility-dialogs-design.md`

## Global Constraints

- Основа — `origin/main`, локальную `main` не менять. Kotlin и Compose Material 3, без новых зависимостей и прав; строки на русском и английском. Не менять сохранённые действия, механизм запуска ярлыков или проверку прав Shizuku.
- До изменения существующих символов выполнить upstream impact в GitNexus и сообщить риск; перед любым коммитом запустить `detect_changes`. Инструментальные тесты запускать адресно на эмуляторе, не на подключённом телефоне.

## Review Focus

- iQOO может быть брендом, а не производителем; регистр не важен — Task 1, `recognizesVivoAndIqoo`.
- Пересоздание окна не снимает стартовую блокировку и не показывает закрытое предупреждение снова — Task 1, `vendorWarningSurvivesRecreateUntilClosed`.
- Установленный Shizuku может не работать или не иметь разрешения; выбирается существующий экран — Task 2, `installedShizukuPreservesExistingAccessFlow`.
- После перехода в магазин состояние установки может измениться — Task 2, `returningToAppRefreshesShizukuPresence`.
- Если приложение магазина или браузер недоступны, переход безопасно завершается — Task 2, `storeUsesHttpsOnlyWhenMarketCannotOpen`.

---

### Task 1: Стартовое предупреждение vivo/iQOO

**Files:**
- Create: `app/src/main/kotlin/com/krayapp/duopress/platform/VendorCompatibility.kt`
- Create: `app/src/main/kotlin/com/krayapp/duopress/ui/common/VendorWarningDialog.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/{AppDependencies,MainActivity}.kt`, `app/src/main/res/{values,values-en}/strings.xml`
- Tests: `app/src/test/kotlin/com/krayapp/duopress/platform/VendorCompatibilityTest.kt`, `app/src/androidTest/kotlin/com/krayapp/duopress/{VendorWarningIntegrationTest,ComposeAppIntegrationTest}.kt`

**Interfaces:** `isSupportedVendor(manufacturer: String, brand: String): Boolean`; `VendorWarningDialog(onClose: (doNotShowAgain: Boolean) -> Unit)`; `AppDependencies` принимает `isSupportedDevice: () -> Boolean` с реальной проверкой по умолчанию и читает/сохраняет отказ по `skip_unsupported_vendor_warning` в настройках. Остальные интеграционные сценарии явно задают поддерживаемый вендор через уже существующий `dependenciesOverride`.

- [ ] **Red.** `recognizesVivoAndIqoo`: `("VIVO","vivo")`, `("BBK","iQOO")`, `("iQoO","other")` → `true`; `("Google","Pixel")` и `("","")` → `false`. UI-тесты проверяют модальность (Назад/тап снаружи ничего не делают), состояние кнопки до и после 3 секунд, повторный запуск без отметки, отметку и снятие отметки, отсутствие диалога после сохранения отказа или при поддерживаемом вендоре. `vendorWarningSurvivesRecreateUntilClosed` проверяет сохранение отметки и отсутствие повторного показа в том же запуске после закрытия.
- [ ] **Verify red.** `bash gradlew :app:testDebugUnitTest --tests '*VendorCompatibilityTest' :app:assembleDebugAndroidTest`; первоначально отсутствуют интерфейс проверки и ресурсы предупреждения. При необходимости собрать с минимальными заглушками для поведенческого red на эмуляторе.
- [ ] **Implement.** Точное сравнение `Build.MANUFACTURER` и `Build.BRAND` без учёта регистра. Рисовать предупреждение в `LauncherContent`, не внутри маршрутов. Использовать `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`, сохраняемые отметку, закрытие и монотонный дедлайн. Через 3 секунды разрешить кнопке закрытие; сохранять итоговую отметку при закрытии. В тестах сохранить и восстановить изменённую настройку.
- [ ] **Green.** `bash gradlew :app:testDebugUnitTest --tests '*VendorCompatibilityTest' :app:assembleDebug :app:assembleDebugAndroidTest`; `VendorWarningIntegrationTest` на эмуляторе проходит без ошибок.
- [ ] Выполнить `detect_changes` и закоммитить только файлы задачи.

### Task 2: Объяснение недоступности «Ярлыков»

**Files:**
- Create: `app/src/main/kotlin/com/krayapp/duopress/platform/ShizukuInstall.kt`, `app/src/main/kotlin/com/krayapp/duopress/ui/common/ShortcutActionTile.kt`
- Modify: `app/src/main/kotlin/com/krayapp/duopress/{AppDependencies,MainActivity}.kt`, `platform/{PlatformRequestHost,ShortcutGateway}.kt`, `picker/ActionPickerScreen.kt`, `menu/{MenuEditorScreen,MenuAddDialog}.kt`, `ui/common/ActionTile.kt`, `app/src/main/res/{values,values-en}/strings.xml` (пути каталога Kotlin относительно пакета).
- Tests: `app/src/androidTest/kotlin/com/krayapp/duopress/{ComposeAppIntegrationTest,platform/ShizukuInstallTest}.kt`

**Interfaces:** `suspend fun isShizukuInstalled(context: Context): Boolean`; `fun openShizukuStore(start: (Intent) -> Boolean): Boolean`; `AppDependencies` получает заменяемую `shizukuInstalled: suspend () -> Boolean`. `ShortcutActionTile(modifier: Modifier, enabled: Boolean, installed: Boolean?, selected: Boolean = false, onOpenShortcuts: () -> Unit, onOpenStore: () -> Boolean)` принимает `null` пока нет результата проверки.

- [ ] **Red.** С фейком `false`: тап «Ярлыки» в обоих сценариях открывает объяснение с двумя кнопками, а не экран выбора. Закрытие сохраняет исходную сетку/экран. Проверить, что плитка объявляет «Требуется Shizuku», а действие «Узнать почему» доступно инструментам доступности. `installedShizukuPreservesExistingAccessFlow` с `true` и fake-статусом `UNAVAILABLE` открывает прежнюю подсказку выбора. `returningToAppRefreshesShizukuPresence`: после смены фейка `false` → `true` и возобновления Activity плитка открывает выбор. `storeUsesHttpsOnlyWhenMarketCannotOpen` проверяет `market://details?id=moe.shizuku.privileged.api`, затем `https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api` только при неудаче первой попытки, и `false`, если не сработали обе.
- [ ] **Verify red.** Собрать с `bash gradlew :app:assembleDebugAndroidTest` и запустить целевой класс на эмуляторе; до изменения нажатие открывает выбор вместо объяснения. Для проверки переходов в маркет допустимы первоначальные ошибки компиляции из-за отсутствия нового интерфейса.
- [ ] **Implement.** Проверять наличие `moe.shizuku.privileged.api` через `PackageManager` в фоне, используя то же имя пакета, что и `ShortcutGateway`. Обновлять результат в launcher при входе/возобновлении; передавать в обе плитки. Общая плитка различает «выбор заблокирован, но доступно объяснение» и «сохранение/лимит, любое нажатие заблокировано». У недоступных ярлыков приглушённый вид, нет стрелки перехода; семантика сообщает причину и назначение нажатия. Диалог оставляет исходный экран на месте; успешный переход в магазин закрывает объяснение, неудачный оставляет его открытым с подсказкой. Открывать ссылки через безопасный launch в `PlatformRequestHost`, не путать его с существующей командой открытия установленного менеджера.
- [ ] **Green.** `bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`; адресно установить оба APK в эмулятор и запустить `com.krayapp.duopress.debug.test/androidx.test.runner.AndroidJUnitRunner`. Критерий: новые классы и существующие сценарии выбора/навигации проходят.
- [ ] Выполнить `detect_changes` и закоммитить только файлы задачи.
