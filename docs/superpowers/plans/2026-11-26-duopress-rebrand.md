# DuoPress Rebrand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or, if explicitly chosen, superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Выпустить ребрендинг DuoPress как отдельное Android-приложение, не меняя его действий или интеграций.

**Architecture:** Переименовать приложение и namespace одного существующего модуля `:app`. Оставить контракт с прошивкой и Shizuku на месте; заменить имена и Android-ресурсы, не меняя бизнес-логику.

**Tech Stack:** Android Gradle Plugin, Kotlin, векторные Android-ресурсы, JUnit, AndroidX Test.

**Spec:** [2026-11-26-duopress-rebrand-design.md](../specs/2026-11-26-duopress-rebrand-design.md).

## Global Constraints

- Основа: `1d8f32b` ветки `refactor/kotlin-compose-mvi`; создать отдельную ветку `rebrand/duopress` в worktree. Не переносить незакоммиченный слайд и не менять исходную рабочую копию.
- Namespace и release `applicationId`: `com.krayapp.duopress`; debug `applicationId`: `com.krayapp.duopress.debug`.
- RU/EN, minSdk 25, прежняя подпись и `vivo_swipe_stub_prefs`. Не менять Vivo action, его метаданные или набор привилегий, не переносить данные из старого пакета.
- Позиционирование: vivo/iQOO с OriginOS. Не вводить проверки прошивки в коде.
- Без push, публикации APK и переименования репозитория.

## Review Focus

- Установленный старый APK и его данные не заменяются новым: проверить совместную установку и изменение назначений в одном пакете без изменения второго.
- Пакет debug не совпадает с namespace: проверить activity, authority и AndroidX-разрешение у обоих вариантов.
- Новый пакет остаётся кандидатом Vivo даже при установленном старом: проверить package-scoped resolution и оба компонента в списке кандидатов.
- Иконка должна открываться на API 25 без adaptive icon; Android 13+ должен получать монохромный слой, а не готовое двухцветное изображение.
- Подпись, permissions и опубликованный внешний контракт не меняются при переносе кода: сравнить оба APK с базой, нормализуя только переименованные идентификаторы и корневые атрибуты бренда.

### Task 1: Новый пакет и идентичность Android

**Файлы:** `app/build.gradle`, `app/src/main/AndroidManifest.xml`, три дерева `app/src/{main,test,androidTest}/kotlin/com/vivotomirpay/stub/`; проверки в `ComposeAppIntegrationTest.kt` и `platform/SwipeEntryContractTest.kt` дерева `androidTest`.

**Контракт:** `SwipeEntryActivity` получает `vivo.intent.action.NFC_BUS_SWINGSPLASH`; authority `${applicationId}.shizuku`; AndroidX-разрешение `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Task affinity: `${applicationId}.swipe` и `${applicationId}.menu`.

- [x] Создать worktree от указанного коммита. Собрать базовые debug/release APK, сохранить их и отчёты `apkanalyzer manifest print` и `apksigner verify --print-certs` вне рабочих деревьев.
- [x] До изменений символов проверить GitNexus impact для затронутых точек; уведомить пользователя о найденном риске. Не переименовывать имена классов и методов: меняются декларации пакетов, ссылки и пути.
- [x] Обновить ожидания instrumented-тестов для нового debug ID, имени класса `com.krayapp.duopress.SwipeEntryActivity`, новых affinity и signature-разрешения. Проверить authority `com.krayapp.duopress.debug.shizuku` и guard `android.permission.INTERACT_ACROSS_USERS_FULL`; сохранить проверки точного фильтра Vivo и его метаданных.
- [x] На локальном тестовом эмуляторе API 33 запустить `bash gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vivotomirpay.stub.platform.SwipeEntryContractTest`. До переименования получить ошибки сравнения прежних и новых идентификаторов, не ошибку компиляции.
- [x] Установить namespace и `applicationId` в `app/build.gradle`. Во всех трёх source sets перенести пакеты в `com/krayapp/duopress` с теми же подпакетами, обновить импорты, квалифицированные имена в коде и ожидаемые компоненты в тестах. Поменять в манифесте две task affinity на placeholders, не менять фильтры, метаданные или настройки provider.
- [x] Запустить `bash gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:connectedDebugAndroidTest`. Проводить adb-проверки только на выбранном эмуляторе, не на физическом телефоне.

### Task 2: Бренд, иконка и проверка APK

**Файлы:** `app/src/main/res/{values,values-en}/strings.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/res/values-night/colors.xml`, `app/src/main/res/drawable/ic_launcher.xml`, `app/src/main/res/drawable/ic_launcher_fg.xml`, `app/src/main/res/drawable-anydpi-v26/ic_launcher.xml`, `settings.gradle`, `README.md`, `build_apk.sh`, `build_apk.ps1`. Новая проверка: `app/src/androidTest/kotlin/com/krayapp/duopress/platform/RebrandResourcesTest.kt`.

**Контракт:** `DuoPress` в обоих языках; фон `#415FFF`; foreground по эскизу **C «Боковая кнопка»** — белый контур телефона, кнопка на правой грани и два коротких штриха справа. Alpha слоя подходит для `<monochrome>`; API 25 имеет цветной fallback.

- [x] Добавить `RebrandResourcesTest`: проверить `app_name`, `swipe_activity_label` и `header_title` как `DuoPress` в контекстах `ru` и `en`; проверить цвет `R.color.ic_launcher_bg` в дневном и ночном режимах; на API 33 проверить, что `AdaptiveIconDrawable.monochrome` не `null` и при отрисовке слоя есть прозрачные и непрозрачные пиксели. На API 25 проверить, что `R.drawable.ic_launcher` загружается как обычный `VectorDrawable`.
- [x] Запустить `bash gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.krayapp.duopress.platform.RebrandResourcesTest`. До обновления ресурсов тесты имени/цвета должны падать со старыми значениями.
- [x] Обновить RU/EN-строки. Перенести геометрию эскиза C в существующий ресурс foreground: в координатах эскиза `100 × 100` контур телефона `x=28, y=23, width=34, height=54, rx=10` со штрихом `7`; правая кнопка и две отметки `M62 37h5v13m10-16v6m0 8v6` со скруглённым штрихом `6.5`. В Android vector использовать viewport `108 × 108`, вписав весь знак в центральную безопасную область adaptive icon. Адаптировать тот же знак в `drawable/ic_launcher.xml` и переиспользовать foreground как монохромный слой. Убрать ночное переопределение синего только для launcher icon; системную тему не менять.
- [x] Обновить имя Gradle-проекта, баннеры сборки и README, включая пути к исходникам, OriginOS без FuntouchOS и сценарий, когда старая версия всё ещё выбрана обработчиком. Исторические документы и реальные URL репозитория не переписывать.
- [x] Запустить `bash gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:connectedDebugAndroidTest`. Проверить отчёты задач и тестов. На доступном API 25 проверить обычную иконку; если такого эмулятора нет, проверить базовый ресурс в собранном APK и отдельно отметить отсутствие проверки на устройстве.
- [x] Для обоих APK сверить `apkanalyzer manifest print`, `apksigner verify --print-certs` и ресурсы иконок. Проверить точные package/namespace, minSdk, activity/authority, неизменные сертификаты, метаданные Vivo и разрешения (кроме ожидаемого имени собственного signature permission).
- [x] На локальном эмуляторе установить старый и новый debug APK одновременно, проверить `pm path` для обоих и запуск `com.krayapp.duopress.debug/com.krayapp.duopress.MainActivity`. Проверить независимость назначений и наличие обоих кандидатов Vivo. Не считать это проверкой двойного нажатия на OriginOS.
- [x] Проверить `git diff --check`, область GitNexus detect_changes и отсутствие старых рабочих идентификаторов в app и сборке. Убедиться, что в исходной рабочей копии изменения слайда остались без правок.

API 25: базовый вектор проверен в APK; доступного эмулятора API 25 не было. Реальное двойное нажатие на OriginOS не проверялось.
