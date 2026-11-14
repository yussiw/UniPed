# UniPed Launcher (v0.1)

Launcher Android TV minimalista (Kotlin, sem Compose, leve para Mali-450).
minSdk 25 (Android 7.1.2). Roda também no celular para teste visual.

## Compilar
Dê push no GitHub. O Actions gera o APK em: Actions > último run > Artifacts.

## Instalar na Bedin
    adb connect 192.168.1.70:5555
    adb install -r app-debug.apk

## Simular controle remoto
    adb shell input keyevent KEYCODE_DPAD_RIGHT   # também DPAD_LEFT/UP/DOWN
    adb shell input keyevent KEYCODE_DPAD_CENTER
    adb shell input keyevent KEYCODE_MENU          # abre Configurações

## Definir como Home
Ao apertar o botão Home, o Android pergunta qual launcher usar.
