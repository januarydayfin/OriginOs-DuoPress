# Мгновенная навигация и пресеты — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** убрать анимации перехода между экранами и три пресета приложений.

**Architecture:** `AppNavHost` задаёт общую политику переходов, а `ActionPreset` и экран пикера оставляют только специальные действия. Обычный выбор приложений и сохранение настроек остаются прежними.

**Tech Stack:** Kotlin, Navigation Compose, Compose UI Test, JUnit.

**Spec:** [Мгновенная навигация и пресеты специальных действий](../specs/2026-11-26-instant-navigation-design.md).

## Global Constraints

- Анимация отдельной панели действий не меняется.
- Mir Pay, СБПэй и Vivo Wallet остаются доступны через список приложений и ручной ввод имени пакета.
- Существующие назначения, значения по умолчанию, специальные действия, ярлыки, внешние запуски, манифест и зависимости не меняются.

## Review Focus

- Проверять исчезновение прежнего экрана до истечения стандартной анимации, а не только конечный результат.
- Проверять, что старый ID кошелька по-прежнему читается, и что приложение можно выбрать из результатов поиска или вручную.

### Task 1: Убрать анимации маршрутов

**Files:** `app/src/main/kotlin/com/vivotomirpay/stub/ui/navigation/AppNavHost.kt`, `app/src/androidTest/kotlin/com/vivotomirpay/stub/ui/navigation/AppNavHostTest.kt`.

**Interfaces:** сигнатура `AppNavHost` и правила стека навигации неизменны.

- [x] Добавить `routeChangesRemoveTheOutgoingScreenWithoutWaitingForAnimation`: с управляемыми часами Compose проверить переключение вкладки, открытие вложенного экрана и Back; после нескольких кадров прежний экран отсутствует.
- [x] Запустить `bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest` и инструментальный тест на выделенном `emulator-5580`, если доступен; зафиксировать RED.
- [x] В общем `NavHost` явно задать `EnterTransition.None`/`ExitTransition.None` для входа, выхода и обоих переходов Back.
- [x] Повторить инструментальный тест и существующие тесты стека.

### Task 2: Оставить в пресетах только специальные действия

**Files:** `app/src/main/kotlin/com/vivotomirpay/stub/picker/ActionPickerViewModel.kt`, `app/src/main/kotlin/com/vivotomirpay/stub/picker/ActionPickerScreen.kt`, `app/src/test/kotlin/com/vivotomirpay/stub/picker/ActionPickerViewModelTest.kt`, RU/EN `strings.xml` при удалении неиспользуемых строк, `README.md` для описания актуального интерфейса.

**Interfaces:** сохраняются `ActionPickerIntent.PickPreset`, `PickApp` и `PickManualPackage`; формат `SettingsSnapshot.targetId` не меняется.

- [x] Добавить `presetsContainOnlyBuiltInSpecialActions`: ожидаемые элементы — Torch, Camera, Vpn, CircleToSearch и Menu. Добавить проверки чтения старого `com.vivo.wallet`, выбора `ru.nspk.mirpay` из поиска и `ru.nspk.sbpay` вручную.
- [x] Запустить `bash gradlew :app:testDebugUnitTest --tests '*ActionPickerViewModelTest'`; подтвердить RED списка пресетов.
- [x] Удалить три элемента и специальные ветки их отображения. Сохранить подсветку приложения в обычном каталоге, ручной ввод и существующие ID. Удалить только точно неиспользуемые подписи.
- [x] Запустить `bash gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` и `git diff --check`.
