# Cookie

**An Android keyboard with offline English dictation.**

Cookie is a modified, unofficial fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard) for non-commercial use. It keeps FUTO's typing, suggestions, layouts, and themes and adds NVIDIA Parakeet as a second speech engine.

[Releases](https://github.com/BreadGenie/cookie-keyboard/releases) · [License](LICENSE.md)

## Features

- **On-device speech recognition.** Dictate with Parakeet TDT 0.6B v2 or Whisper. After you import Parakeet, Cookie uses it by default unless you've already selected an engine.
- **Long-form dictation.** Parakeet processes speech while you talk and inserts one final transcript when you stop. Cancel keeps the text you already typed.
- **Personal dictionary.** Parakeet uses your dictionary entries as hints for names and specialized terms.
- **Verified model import.** Cookie checks the size and SHA-256 of each imported model file.

Cookie has been used on the OnePlus 13. Compatibility and performance on other devices have yet to be verified.

## Install from source

Build from source with JDK 17, Android SDK 35, NDK `28.0.13004108`, CMake, Python 3, and Git LFS.

```sh
git lfs install
git clone --recurse-submodules https://github.com/BreadGenie/cookie-keyboard.git
cd cookie-keyboard
python3 tools/whistle/provision.py
./gradlew assembleCookieDebug
adb install -r build/outputs/apk/cookie/debug/futo-keyboard-cookie-debug.apk
```

Enable and select Cookie in Android's keyboard settings, then grant microphone access when prompted. In Cookie's **Voice input** settings, import the Parakeet ZIP or the five model files. Use the [pinned Parakeet export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/tree/84b478d541cdbf0c296a7ab5f1e20a6f8f998114) and the [Silero VAD file](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx). Cookie keeps its own settings and models when installed alongside FUTO Keyboard.

## License and credits

Cookie is a modified version of FUTO Keyboard. The [FUTO Source First License 1.1-kb](LICENSE.md) governs the FUTO code and permits non-commercial distribution free of charge. Cookie retains FUTO's copyright, license notices, and payment functionality. See [NOTICE](NOTICE) for AOSP LatinIME attribution.

Parakeet TDT 0.6B v2 is by NVIDIA under CC BY 4.0. Cookie uses sherpa-onnx's int8 export with ONNX Runtime and Silero VAD. The app includes the relevant license notices.
