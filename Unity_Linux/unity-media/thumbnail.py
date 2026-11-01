#!/usr/bin/env python3
"""Decode an advertised media cover into a bounded JPEG, without a display."""
import base64
import json
import hashlib
import io
import os
from pathlib import Path
import stat
import sys
import urllib.parse
import urllib.request
from PIL import Image, ImageOps

MAX_INPUT = 4 * 1024 * 1024

def thumbnail(uri):
    parsed = urllib.parse.urlsplit(uri)
    if parsed.scheme == 'file' and parsed.netloc in ('', 'localhost'):
        path = urllib.request.url2pathname(parsed.path)
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as source:
            metadata = os.fstat(source.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_INPUT:
                raise ValueError('Cover is not a bounded regular image')
            data = source.read(MAX_INPUT + 1)
    elif parsed.scheme in ('https', 'http') and parsed.hostname and not parsed.username and not parsed.password:
        request = urllib.request.Request(uri, headers={'User-Agent': 'Unity/0.1', 'Accept': 'image/jpeg,image/png,image/webp'})
        with urllib.request.urlopen(request, timeout=2) as source:
            if urllib.parse.urlsplit(source.url).scheme not in ('http', 'https'):
                raise ValueError('Invalid cover redirect')
            data = source.read(MAX_INPUT + 1)
    else:
        raise ValueError('Unsupported artwork URL')
    if len(data) > MAX_INPUT or not (data.startswith(b'\xff\xd8') or data.startswith(b'\x89PNG\r\n\x1a\n') or data.startswith(b'RIFF') and data[8:12] == b'WEBP'):
        raise ValueError('Artwork is not a supported bounded image')
    with Image.open(io.BytesIO(data), formats=('JPEG', 'PNG', 'WEBP')) as source:
        if source.width <= 0 or source.height <= 0 or source.width * source.height > 16_777_216:
            raise ValueError('Artwork dimensions exceed limit')
        image = ImageOps.exif_transpose(source)
        image = image.convert('RGB')
        result = encode(image, (1024, 768, 512, 320), (92, 88, 84, 80), 192 * 1024)
        result['id'] = hashlib.sha256(base64.b64decode(result['data'])).hexdigest()
        result['preview'] = encode(image, (128, 96, 64), (80, 65, 50, 35, 20), 2048)
        return result

def encode(image, edges, qualities, budget):
    for edge in edges:
        scaled = image.copy(); scaled.thumbnail((edge, edge), Image.Resampling.LANCZOS)
        for quality in qualities:
            output = io.BytesIO(); scaled.save(output, format='JPEG', quality=quality, optimize=True)
            encoded = output.getvalue()
            if len(encoded) <= budget:
                return {'mime': 'image/jpeg', 'width': scaled.width, 'height': scaled.height, 'data': base64.b64encode(encoded).decode('ascii')}
    raise ValueError('Artwork could not fit cover budget')

if __name__ == '__main__':
    try:
        if len(sys.argv) != 2 or len(sys.argv[1]) > 8192: raise ValueError('Invalid artwork URL')
        print(json.dumps(thumbnail(sys.argv[1])))
    except Exception:
        raise SystemExit(1)
