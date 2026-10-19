floating-window.mp4 is a generated, silent four-second H.264 test pattern for
LiveFloatingPlaybackTest. It is packaged only in the instrumentation test APK.
Recreate with:
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15 -t 4 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -an floating-window.mp4
