# EPV Vector Bridge — интеграция Google Фото через LSPosed/Vector

Это **отдельный Xposed APK-модуль**, не ZIP для KernelSU/Magisk. Он работает вместе
с [Encrypted Photo Vault](https://github.com/Mitchelded/Encrypted-Photo-Vault)
и **Vector Framework**.

## Что делает

- В приложении Google Фото появляется компактная панель **EPV | 18+** в правом нижнем углу.
- **EPV** открывает основное приложение Encrypted Photo Vault с подсказкой,
  как выбрать шифрованные PNG из Google Фото через системный Photo Picker.
- **18+** открывает основное приложение и его локальный сканер.
  Для запуска нужна доступная на телефоне папка, пароль и порог. Текущий
  список проверенных файлов и возможность «Продолжить» хранятся в основном EPV.
- Без Xposed обычное Android «Поделиться» → **Encrypted Photo Vault** тоже
  позволяет передать выбранные Google Фото изображения или видео в EPV
  (требуется подтверждение шифрования и пароль).
- Никаких изменений APK Google Фото, подмены Pixel и обхода проверок Google.

## Установка

1. Установи **Encrypted Photo Vault** (основной Android APK) из workflow
   [Android debug APK](https://github.com/Mitchelded/Encrypted-Photo-Vault/actions/workflows/android.yml).
2. Установи APK `EPV-Vector-Bridge-debug-apk` из workflow
   [EPV Vector LSPosed module APK](https://github.com/Mitchelded/Encrypted-Photo-Vault/actions/workflows/vector-bridge.yml).
3. Открой установленный Vector/LSPosed Manager и включи **EPV Vector Bridge**.
4. В **Scope/Область действия** поставь **только Google Фото**
   (`com.google.android.apps.photos`). Не выбирай System Framework,
   system_server, SystemUI и все приложения.
5. Принудительно останови Google Фото и запусти его снова.
6. Кнопки должны появиться внизу справа. При нажатии откроется EPV.

Если EPV не установлен, модуль выводит уведомление о необходимости установки.

## Ограничения Google Фото

- Модуль **не получает полный список облачной библиотеки Google Фото** и
  **не читает внутренние токены** Google, не открывает закрытые API.
- Для облачных изображений доступен только системный выбор пользовательских
  файлов (Android Photo Picker), обычно с ограничением до 100 PNG за раз.
- Переданное через «Поделиться» медиа может быть временной/преобразованной
  копией, а не точным исходником. Для контейнеров EPV1 важно использовать
  оригинальные PNG-пиксели и все части.
- 18+ классификация выполняется **внутри EPV**, локально и только по тем файлам,
  к которым Android дал доступ. Вся библиотека Google Фото автоматически
  не сканируется.
- Плавающая панель вводится через стандартный lifecycle hook Activity.onResume,
  поэтому может визуально пересекаться с элементами новых версий Google Фото.
  Если мешает, выключи модуль для Google Фото в Vector.
- Изменения Google Фото или Vector могут нарушить работу hook. Модуль
  намеренно не использует обфусцированные внутренние классы Google.
- Потребляется **права процесса Google Фото**, не root. Сам EPV не получает root
  и ничего не читает из закрытого хранилища Google Фото.

## Совместимость

Проверяемая базовая платформа: Android 17, Xposed legacy API 82 под Vector.
Vector публично заявляет поддержку Android 8.1–17 Beta, но работа на
конкретной сборке OriginOS/Vector зависит от версии Zygisk и возможности
перезапустить нужный процесс. APK строится Gradle из `android/vector-bridge`,
платформа Xposed предоставляет `de.robv.android.xposed.*` в runtime.

Для возврата изменений достаточно выключить **EPV Vector Bridge** в Vector
и принудительно перезапустить Google Фото. Системные разделы не изменяются.
