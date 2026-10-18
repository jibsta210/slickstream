# Native Cast integration check

This exercises the actual bundled Android libVLC, the authenticated HTTP bridge, Media3 metadata
conversion, pause/play, seeking, and output cleanup. The fixture implements enough Cast V2 over TLS
to request the converted bytes. It does **not** verify a physical Chromecast's decoder or the Google
Cast SDK discovery/session UI.

Use a project-local scratch directory and an x86_64 Android emulator. Requires Python 3, OpenSSL,
FFmpeg, the Android SDK and JDK 17. From the repository:

```bash
cast_scratch=/home/jakes/Projects/SlickStream/scratch/cast-native-test
mkdir -p "$cast_scratch"
ffmpeg -v error -f lavfi -i testsrc2=size=320x240:rate=24 -f lavfi -i 'aevalsrc=0|0|0.25*sin(2*PI*440*t)|0|0|0:s=48000:c=5.1' -t 30 -c:v mpeg4 -q:v 5 -c:a ac3 -y "$cast_scratch/fixture.mkv"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$cast_scratch/key.pem" -out "$cast_scratch/cert.pem" -days 1 -subj /CN=localhost
python3 tools/cast-smoke-test/receiver.py "$cast_scratch"
```

In another terminal, with only the test emulator selected (use `adb -s SERIAL` if needed):

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PcastEmulatorTest=true
adb forward tcp:18080 tcp:18080
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.slickstream.cast.VlcCastIntegrationTest -e castTestHost 10.0.2.2 com.slickstream.test/androidx.test.runner.AndroidJUnitRunner
ffprobe -v error -show_entries stream=codec_name,codec_type,width,height -of json "$cast_scratch/converted.mkv"
ffmpeg -v error -i "$cast_scratch/converted.mkv" -f null -
python3 tools/cast-smoke-test/verify_audio.py "$cast_scratch/converted.mkv"
```

Expect four passing tests, H.264 video and AAC-LC stereo audio, and no decode errors. The fixture has
sound ONLY in the 5.1 center channel; the audio check requires audible samples in both stereo channels.
Run `com.slickstream.feature.player.CastPlaybackControlsTest` with the same instrumentation runner
for the remote-control UI checks. Optional `-e castScreenshot filename.png` saves a screenshot under
the target app's external files directory. Check both portrait and landscape emulator orientations.
Restart the fixture receiver before repeating the test so its counters reset. Remove the adb forward,
stop the test emulator/server and delete disposable fixtures/certificates after testing. Release builds
must omit `-PcastEmulatorTest=true`; the normal build includes the existing ARM ABIs.
