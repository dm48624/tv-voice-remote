# TV Voice Remote

An Android tablet app that controls a Samsung smart TV (Tizen, e.g. UN50TU700D) and a Roku stick by voice.

Say things like:

- "Go to 9&10" / "Put on channel 9" / "Channel up"
- "Find NCIS" (searches across Roku apps)
- "Open Paramount Plus"
- "Volume up" / "A little quieter" / "Mute"
- "Pause", "Rewind", "Down 3 times", "Select"
- "Turn off the TV"
- "Is there local news on now?" (opens the TV guide for now)

## Install on the tablet

On the tablet, open this link in Chrome and install the file it downloads:

**https://github.com/OWNER/tv-voice-remote/releases/latest/download/tv-voice-remote.apk**

Android will ask to allow installs from Chrome the first time. Newer versions install over the old one with the same link.

## Setup

1. Open the app, tap **Settings**, then **Find my TV and Roku on the Wi-Fi**.
2. Tap **Pair with the TV** and choose **Allow** on the TV when it asks.
3. Edit the **Channels** list so the names you say match your channel numbers.

TV and Roku settings that help:

- TV: Settings → General → Network → Expert Settings → **Power On with Mobile** (lets the app turn the TV on).
- TV: Settings → General → External Device Manager → **Anynet+ (HDMI-CEC)** on (lets the Roku switch the TV to its input).
- Roku: Settings → System → Advanced system settings → Control by mobile apps → **Network access: Permissive** if the Roku ignores the app.

## How it works

- Samsung TV: local remote-control WebSocket (`wss://TV:8002`), the same one Samsung's SmartThings remote uses.
- Roku: External Control Protocol (`http://ROKU:8060`).
- Voice: Android's built-in speech recognition; replies are spoken with text-to-speech.
- Commands are understood by `CommandParser.kt` (tested in `CommandParserTest.kt`).

Every push to `main` builds the app on GitHub Actions and publishes a new release.
