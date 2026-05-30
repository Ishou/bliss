import sys
from pathlib import Path

# Digit-prefixed job filenames aren't importable as packages; tests import
# sibling modules (style_allocation) by plain name, so put this dir on the path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
