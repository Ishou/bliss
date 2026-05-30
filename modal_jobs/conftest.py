import sys
from pathlib import Path

# Tests import sibling modules by plain name; the digit-prefixed jobs block package import.
sys.path.insert(0, str(Path(__file__).resolve().parent))
