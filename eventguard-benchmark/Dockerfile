FROM python:3.11-slim

WORKDIR /app

# 评测器依赖：HTTP/Kafka/Postgres 客户端 + matplotlib（HTML 报告图表）+ websocket-client（WS 握手）
COPY requirements.txt .
# ponytail: 国内部署直接写死腾讯云 PyPI 镜像（本机实测官方 pypi 下载仅 ~124 B/s 卡死，腾讯云镜像 110 MB/s）。
# 与 eventguard-ai/Dockerfile 同一约定：不用 build arg（曾因空值导致 "-r" 报错）。
RUN pip install --no-cache-dir -i https://mirrors.cloud.tencent.com/pypi/simple -r requirements.txt

COPY . .

# 默认只跑 functional（正确性/准确率）；负载套件需限流关闭后 --suites load
ENTRYPOINT ["python", "run.py"]
CMD ["--suites", "functional"]
