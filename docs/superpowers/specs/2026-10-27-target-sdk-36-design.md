# Target SDK 36

Поднять `targetSdk` с 35 до 36 для Android 16 и обновить инструменты сборки,
после проверки опубликовать изменения в `origin/main`. `compileSdk` уже равен 36.

**Подходы.** Изменение только `targetSdk` даёт минимальный diff, но оставляет AGP
8.7.0, не поддерживающий компиляцию с API 36. Выбран согласованный апгрейд: AGP
8.10.1, Gradle 8.11.1 и Kotlin Gradle Plugin 2.2.21 (включая Compose и
Serialization) — последний нужен, чтобы не выйти за поддерживаемый диапазон AGP
для Kotlin 2.1.21. Миграция на AGP 9 и обновление библиотек приложения не нужны.

**Границы.** Сохранить `compileSdk 36`, `minSdk 25`, версию и идентификаторы
приложения, настройки подписи и объявленные зависимости в `app/build.gradle`.
Не менять исходники или манифест приложения. На Android 16 проверить навигацию
и отображение при наличии эмулятора; сборка и unit-тесты не заменяют такую проверку.

**Приёмка.** Gradle сообщает версию 8.11.1, debug и release собираются, unit-тесты
проходят. В манифестах обоих APK подтверждены `targetSdkVersion 36` и
`minSdkVersion 25`. В коммит не попадают чужие файлы и локальные артефакты.

Источники: [AGP 8.10](https://developer.android.com/build/releases/agp-8-10-0-release-notes),
[совместимость Kotlin Gradle Plugin](https://kotlinlang.org/docs/gradle-configure-project.html),
[изменения поведения Android 16](https://developer.android.com/about/versions/16/behavior-changes-16).
