import sys
from pathlib import Path

# 将 eventguard-ai 目录加入 sys.path，使 app.* 可导入
sys.path.insert(0, str(Path(__file__).parent.parent))
