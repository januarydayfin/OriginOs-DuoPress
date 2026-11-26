# Карточки экрана «Действия» — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** при открытии экрана первым читается назначенное действие, а при необходимости настройки видна системная кнопка.

**Architecture:** `ActionsScreen` отображает прежний `ActionsState` и передаёт события тем же `onOpen` и `onIntent`. Все изменения интерфейса локальны для корневого экрана «Действия».

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI Test.

**Spec:** [Согласованный дизайн](../specs/2026-11-26-actions-cards-design.md).

## Global Constraints

- Использовать цвета `AppTheme`, существующие строковые ресурсы и формы множественного числа.
- Тап по карточке редактирует назначение, а не запускает его. Нижняя навигация принадлежит хосту.

## Review Focus

1. Пока настройки загружаются, на карточках нет фиктивного назначения или счётчика. После загрузки пустого меню отображается настоящий ноль.
2. Заголовок «Действия» и одноимённую вкладку можно различить по семантике.
3. Длинное имя действия при крупном системном шрифте переносится; до последней карточки можно прокрутить.
4. При другом обработчике открываются настройки именно его пакета; при отсутствии выбранного обработчика предлагается настройка кнопки.
5. Пока системная команда выполняется, второй тап недоступен; ошибка открытия видна рядом с кнопкой.

### Задача: заменить строки карточками и проверить сценарии экрана

**Файлы:** `app/src/main/kotlin/com/krayapp/duopress/actions/ActionsScreen.kt` — компоновка, карточки и блок статуса. `app/src/androidTest/kotlin/com/krayapp/duopress/actions/ActionsScreenTest.kt` — состояния экрана. `app/src/androidTest/kotlin/com/krayapp/duopress/ComposeAppIntegrationTest.kt` — заголовок и навигация.

**Интерфейсы:** сохранить `ActionsScreen(state: ActionsState, onIntent: (ActionsIntent) -> Unit, onOpen: (AppRoute) -> Unit, modifier: Modifier = Modifier)` и `HandlerSummary(state: ActionsState, onIntent: (ActionsIntent) -> Unit)`. Новые элементы — приватные функции в файле экрана.

- [ ] В интеграционном тесте заменить ожидание `heading(R.string.app_name)` на `heading(R.string.nav_actions)`. Запустить тест на исходном экране и убедиться, что не найден именно новый заголовок. Сохранить проверку `tab(R.string.nav_actions).assertIsSelected()`.
- [ ] В `ActionsScreenTest` проверить переход от `settings = null` к пустому меню: локализованный счётчик нуля сначала отсутствует, после загрузки виден. Для `OtherApp` и `NoSpecificHandler` проверить подпись кнопки и соответствующий `ActionsIntent`; при `platformBusy` — `assertIsNotEnabled()`, при `platformLaunchFailed` — видимое сообщение ошибки.
- [ ] В `ActionsScreen` использовать заголовок `nav_actions`, затем `HandlerSummary`, акцентную карточку (`primaryContainer`) и две нейтральные (`surfaceContainer`). По макету: внешние поля 16 dp, интервал карточек 12 dp, скругление 20 dp; минимальная высота основной 148 dp, остальных 84 dp. Основное назначение — `headlineLarge`; тексты растут без фиксированной высоты, шевроны и иконки декоративные. Сохранить общий кликабельный узел на карточку и расчёт через `visibleMenuItems`.
- [ ] В `HandlerSummary` оставить компактный текст для `ThisApp`, `Loading` и `Unavailable`; для состояний с доступной командой объединить статус, кнопку и возможную ошибку в отдельную карточку. Сохранить объявления изменений для TalkBack.
- [ ] На эмуляторе проверить светлую и тёмную темы, затем узкий экран с масштабом шрифта 200% и длинным названием приложения: видны обе строки назначения, шеврон не перекрывает текст, меню достижимо прокруткой. Сравнить обычный масштаб с утверждённым макетом.
- [ ] Запустить `./gradlew :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.krayapp.duopress.actions.ActionsScreenTest,com.krayapp.duopress.ComposeAppIntegrationTest`. Критерий: сборка и оба тестовых класса проходят, в том числе существующая проверка трёх маршрутов.
- [ ] Перед коммитом проверить diff и `gitnexus.detect_changes` для подготовленных файлов. Коммит: `feat: оформить действия карточками Material 3`.
