#!/usr/bin/env python3
"""Real bounded artwork decoding, with no external services or display."""
import base64
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('thumbnail',ROOT/'unity-media/thumbnail.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Thumbnails(unittest.TestCase):
 def test_bounded_jpeg_and_encoded_local_filename(self):
  with tempfile.TemporaryDirectory() as directory:
   cover=Path(directory)/'cover # தமிழ்.jpg';shutil.copyfile(ROOT/'tests/fixtures/preview.jpg',cover)
   result=module.thumbnail(cover.as_uri());data=base64.b64decode(result['data'])
   self.assertLessEqual(len(data),192*1024);self.assertLessEqual(max(result['width'],result['height']),1024)
   self.assertLessEqual(len(base64.b64decode(result['preview']['data'])),2048)
   self.assertLessEqual(max(result['preview']['width'],result['preview']['height']),128)
   self.assertTrue(data.startswith(b'\xff\xd8') and data.endswith(b'\xff\xd9'))
 def test_original_cover_keeps_detail_and_has_a_separate_legacy_preview(self):
  import hashlib
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'large.png';Image.new('RGB',(1600,1200),(30,100,200)).save(path)
   result=module.thumbnail(path.as_uri());data=base64.b64decode(result['data'])
   self.assertEqual((result['width'],result['height']),(1024,768))
   self.assertEqual(result['id'],hashlib.sha256(data).hexdigest())
   self.assertEqual(result['preview']['width'],128)
 def test_rejects_bad_schemes_symlinks_and_oversized_sources(self):
  with self.assertRaises(ValueError):module.thumbnail('data:image/png;base64,AAAA')
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);link=root/'link.jpg';link.symlink_to(ROOT/'tests/fixtures/preview.jpg')
   with self.assertRaises(OSError):module.thumbnail(link.as_uri())
   large=root/'large.jpg'
   with large.open('wb') as output:output.truncate(module.MAX_INPUT+1)
   with self.assertRaises(ValueError):module.thumbnail(large.as_uri())
   corrupt=root/'corrupt.jpg';corrupt.write_bytes(b'not an image')
   with self.assertRaises(ValueError):module.thumbnail(corrupt.as_uri())
if __name__=='__main__':unittest.main(verbosity=2)
