# EventGuard AI 服务

FastAPI 检测服务，消费 Kafka 事件，执行规则/统计/模型检测，并提供异常查询与自然语言查询接口。

- `app`：API、Kafka 消费、检测器、存储和指标。
- `tests`：服务单测。
- `training`：离线数据生成与模型训练脚本。
- `models`、`data`：运行所需模型与样本资产。

本地运行：安装 `requirements.txt` 后执行 `uvicorn app.main:app --reload`；生产/联调使用根目录 Docker Compose。
