FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
# ponytail: pip 索引源可经 build arg 覆盖；默认官方 PyPI，国内部署传腾讯/清华镜像避免超时
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir -i ${PIPE_INDEX_URL} -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
