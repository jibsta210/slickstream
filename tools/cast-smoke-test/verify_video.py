"""Require compatible H.264 and real changing pictures, not merely a video stream declaration."""
import json
import subprocess
import sys

path = sys.argv[1]
probe = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
    "stream=codec_name,profile,level,pix_fmt,width,height,r_frame_rate", "-of", "json", path,
]))
video = probe["streams"][0]
assert video["codec_name"] == "h264", video
assert video["profile"] in ("Baseline", "Constrained Baseline", "Main", "High"), video
assert video["level"] <= 41 and video["pix_fmt"] == "yuv420p", video
assert 0 < video["width"] <= 1920 and 0 < video["height"] <= 1080, video
num, den = map(int, video["r_frame_rate"].split("/"))
assert den > 0 and 0 < num / den <= 30, video
frames = subprocess.check_output([
    "ffmpeg", "-v", "error", "-xerror", "-i", path, "-map", "0:v:0", "-t", "5",
    "-vf", "scale=64:36,fps=5", "-pix_fmt", "gray", "-f", "rawvideo", "-",
])
size = 64 * 36
count = len(frames) // size
assert count >= 5, f"Too few decoded video frames: {count}"
images = [frames[i * size:(i + 1) * size] for i in range(count)]
for image in images:
    assert max(image) - min(image) > 50 and sum(image) / size > 20, "Black/blank decoded frame"
assert len(set(images)) > 1, "Frozen picture"
print(json.dumps({"video": video, "decodedFrames": count, "nonBlackMovingPicture": True}, indent=2))
