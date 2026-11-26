# Remote Control — Universal TV Remote (Android TV & Samsung Tizen)

[![Release](https://img.shields.io/github/v/release/Otsgolyak/Remote_control?label=Latest%20Release&color=007AFF)](https://github.com/Otsgolyak/Remote_control/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Otsgolyak/Remote_control/android.yml?branch=main&label=Build)](https://github.com/Otsgolyak/Remote_control/actions)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)

Современный нативный Wi-Fi + Bluetooth пульт управления для **Android TV / Google TV (Xiaomi, Sony, TCL, Mi Box)** и **Samsung Smart TV (Tizen)**, написанный на Kotlin и Jetpack Compose (Material 3).

---

## 📲 Скачать и установить на смартфон

Прямые ссылки на актуальную версию:

- 🚀 **[Скачать Remote_control.apk (прямая ссылка)](https://github.com/Otsgolyak/Remote_control/releases/latest/download/Remote_control.apk)**
- 📦 **[Скачать app-debug.apk](https://github.com/Otsgolyak/Remote_control/releases/latest/download/app-debug.apk)**
- 📋 **[Страница всех релизов и список изменений](https://github.com/Otsgolyak/Remote_control/releases/latest)**

### 📷 Установка по QR-коду
Наведите камеру смартфона на QR-код для мгновенного скачивания `.apk`:

![QR-код для скачивания APK](https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=https://github.com/Otsgolyak/Remote_control/releases/latest/download/Remote_control.apk)

---

## ✨ Основные возможности

- 📺 **Универсальная поддержка ТВ**:
  - **Android TV / Google TV**: работа по защищенному протоколу *Android TV Remote Service v2* (mTLS, порт 6466/6467).
  - **Samsung Smart TV**: подключение по WebSocket API (Tizen OS, порт 8002).
- 🖱️ **Bluetooth Air Mouse (Аэромышь)**:
  - Управление системным курсором мыши на ТВ с помощью встроенного гироскопа смартфона (профиль *Bluetooth HID Device*).
  - Нажатия левой и правой кнопок мыши, колесо прокрутки, калибровка гироскопа.
- 🎙️ **Голосовое управление**:
  - Запись речи с микрофона телефона и прямая передача аудиопотока на ТВ для Google Assistant / голосового поиска.
- 🚀 **Быстрый запуск приложений**:
  - Настраиваемые плитки быстрого запуска (YouTube, Кинопоиск, Netflix, RuTube, VLC, Онлайн-кинотеатры и др.).
  - Удобный диалог добавления с фильтрацией еще не добавленных установленных приложений.
- ⌨️ **Ввод текста**:
  - Передача символов и текста с экранной клавиатуры телефона прямо в поля ввода на ТВ.
- 🎨 **Темы оформления**:
  - Переключение между стильной темной и светлой темами с сохранением выбора.
- 🔄 **Автообновления и стабильность**:
  - Встроенная проверка новых версий прямо из приложения с автоскачиванием.
  - Автоматическая отправка отчетов об ошибках для оперативных исправлений.
