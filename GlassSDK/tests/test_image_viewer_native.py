"""Exercise the actual image viewer C plugin with a framebuffer and Bluetooth Host."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

SDK = Path(__file__).resolve().parents[1]
CC = os.environ.get('CC', 'cc')

@unittest.skipUnless(shutil.which(CC), 'native C compiler required')
class ImageViewerTests(unittest.TestCase):
    def test_transfer_render_and_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='image-viewer-test-') as folder:
            binary = Path(folder) / 'test'
            subprocess.run([CC, '-std=c11', '-Wall', '-Wextra', '-Werror',
                *shlex.split(os.environ.get('CFLAGS', '')),
                '-I', str(SDK / 'include'), str(SDK / 'tests/test_image_viewer.c'),
                '-o', str(binary), *shlex.split(os.environ.get('LZ4_LINK_FLAGS', '-llz4'))], check=True)
            subprocess.run([str(binary)], check=True)

if __name__ == '__main__':
    unittest.main()
