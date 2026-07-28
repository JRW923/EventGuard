FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
# ponytail: 国内部署直接写死腾讯云 PyPI 镜像；曾因 PIP_INDEX_URL 经 compose build args 传递出现空值（“-r” 报错）而失败，故写死，不再依赖 build arg
RUN pip install --no-cache-dir -i https://mirrors.cloud.tencent.com/pypi/simple -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
