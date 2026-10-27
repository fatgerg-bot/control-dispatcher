# Control

An Android app that plays a UK police control room over a walkie-talkie. It listens to a base radio through a cable, turns each over into text on the phone, asks Claude what Control should say, and speaks the reply back over the radio on VOX.

## Install

1. On the phone, open the latest release on this repo's Releases page and download `control.apk`.
2. Open it and allow installing from your browser if Android asks.
3. Later builds install over the top; your settings are kept.

## First run

1. Allow the microphone and notifications when asked.
2. Open **Settings** and fill in:
   - **Claude API key.** It is stored only on the phone and never in this repo.
   - **Officers**, one per line, for example `Wolf One = Officer Wolf` and `Fox Two = Sam Fox`.
   - **Text this number on a safety stop**: your mobile number.
3. Tap **Allow Do Not Disturb control** and **Stop the phone putting Control to sleep**.
4. Save.

## Setting up the cable and radio

1. Plug in the cable and check the top of the screen says **Cable connected**.
2. Put the base radio on VOX, on the same channel and privacy code as the handsets.
3. Tap **Test tone**. The base radio should transmit for 3 seconds. If it doesn't, raise **Output level** or the radio's VOX sensitivity.
4. Talk on a handset and watch **Radio input level**. The bar should turn green while you talk and drop back to grey between overs. If it stays grey, lower **Talk threshold**; if it's green during silence, raise it.
5. Type something in **Say this over the radio** and tap Send to check how Control sounds.

## Playing

Tap **Start shift**. Control calls the officers and starts giving jobs. Tap **End shift** to stand everyone down. Each shift's log is saved on the phone.

## Safety

- Trip words such as "I'm hurt" or "a stranger" stop the game before Claude sees anything. Control tells the officers to find a grown-up, the phone sounds an alarm, and your number gets a text.
- Claude can also stop the game if something sounds real.
- The game stays stopped until a grown-up taps **Everything's OK, resume the game**.

## How it's built

- Kotlin and Jetpack Compose, built by GitHub Actions (`.github/workflows/build.yml`).
- Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with the Moonshine English model, running on the phone.
- Control's voice: the phone's own British text-to-speech.
- Control's brain: the Claude API.
