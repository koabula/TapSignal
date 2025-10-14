# Benchmark Test Assets

Place pre-generated test media files in this directory to improve test realism.

## Required Files

### Voice Messages
- `voice_10s.m4a` - 10 second audio (SMALL size)
- `voice_30s.m4a` - 30 second audio (MEDIUM size)
- `voice_60s.m4a` - 60 second audio (LARGE size)

### Images
- `image_100kb.jpg` - ~100KB image (SMALL size)
- `image_500kb.jpg` - ~500KB image (MEDIUM size)
- `image_2mb.jpg` - ~2MB image (LARGE size)

### Videos
- `video_5mb.mp4` - ~5MB video (SMALL size)
- `video_20mb.mp4` - ~20MB video (MEDIUM size)
- `video_50mb.mp4` - ~50MB video (LARGE size)

## Fallback Behavior

If these files are not present, the benchmark system will:
- Generate placeholder images (with random patterns to prevent excessive compression)
- Generate binary dummy files for voice/video (not real media, just for size testing)

## Generating Test Files

You can use ffmpeg to generate test media:

```bash
# Generate silent audio files
ffmpeg -f lavfi -i anullsrc -t 10 -c:a aac -b:a 128k voice_10s.m4a
ffmpeg -f lavfi -i anullsrc -t 30 -c:a aac -b:a 128k voice_30s.m4a
ffmpeg -f lavfi -i anullsrc -t 60 -c:a aac -b:a 128k voice_60s.m4a

# Generate test video files
ffmpeg -f lavfi -i color=c=blue:s=1280x720 -t 5 -c:v libx264 -b:v 8M video_5mb.mp4
ffmpeg -f lavfi -i color=c=blue:s=1280x720 -t 10 -c:v libx264 -b:v 16M video_20mb.mp4
ffmpeg -f lavfi -i color=c=blue:s=1280x720 -t 20 -c:v libx264 -b:v 20M video_50mb.mp4
```

Or use the Python script to generate test images:

```python
from PIL import Image, ImageDraw
import random

def generate_test_image(output_path, target_size_kb):
    img = Image.new('RGB', (1920, 1080))
    draw = ImageDraw.Draw(img)
    
    # Fill with random colors
    for _ in range(100):
        x1, y1 = random.randint(0, 1920), random.randint(0, 1080)
        x2, y2 = random.randint(0, 1920), random.randint(0, 1080)
        color = (random.randint(0, 255), random.randint(0, 255), random.randint(0, 255))
        draw.rectangle([x1, y1, x2, y2], fill=color)
    
    # Save with adjusted quality
    quality = 85
    img.save(output_path, 'JPEG', quality=quality)

generate_test_image('image_100kb.jpg', 100)
generate_test_image('image_500kb.jpg', 500)
generate_test_image('image_2mb.jpg', 2048)
```

