"""Verify the center-only 5.1 fixture becomes non-silent AAC stereo, not merely a valid container."""
import array
import json
import math
import subprocess
import sys

path = sys.argv[1]
probe = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error", "-show_entries", "stream=codec_name,profile,codec_type,channels,sample_rate",
    "-of", "json", path,
]))
audio = next(stream for stream in probe["streams"] if stream["codec_type"] == "audio")
assert audio["codec_name"] == "aac" and audio["profile"] == "LC", audio
assert audio["channels"] == 2 and audio["sample_rate"] == "48000", audio
pcm = array.array("f", subprocess.check_output([
    "ffmpeg", "-v", "error", "-xerror", "-i", path, "-map", "0:a:0", "-t", "5", "-f", "f32le", "-",
]))
if sys.byteorder != "little":
    pcm.byteswap()
assert len(pcm) > 48000 * 2, "Less than one second of decoded audio"
levels = []
for channel in range(2):
    samples = pcm[channel::2]
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
    assert rms > 0.01, f"Missing/inaudible center-channel downmix in channel {channel + 1}: {rms}"
    levels.append(20 * math.log10(rms))
print(json.dumps({"audio": audio, "rmsDb": levels, "centerChannelPreserved": True}, indent=2))
