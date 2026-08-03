FROM python:3.11-slim

WORKDIR /app

# 评测器依赖：HTTP/Kafka/Postgres 客户端 + matplotlib（HTML 报告图表）+ websocket-client（WS 握手）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 默认只跑 functional（正确性/准确率）；负载套件需限流关闭后 --suites load
ENTRYPOINT ["python", "run.py"]
CMD ["--suites", "functional"]
