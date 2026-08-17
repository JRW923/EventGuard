FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
# ponytail: 国内部署写死 npmmirror，避免 npmjs.org 超时（同 AI 镜像写死思路）
# 有 lock 文件时用 npm ci 保证可复现构建；构建前 type-check，类型错误直接让镜像构建失败
RUN if [ -f package-lock.json ]; then \
        npm ci --registry=https://registry.npmmirror.com; \
    else \
        npm install --registry=https://registry.npmmirror.com; \
    fi
COPY . .
RUN npm run type-check --if-present && npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# ponytail: 覆盖默认配置，使生产镜像代理 API/WS 到后端（vite proxy 仅开发期有效）
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY nginx-entrypoint.sh /nginx-entrypoint.sh
RUN chmod +x /nginx-entrypoint.sh
EXPOSE 80
ENTRYPOINT ["/nginx-entrypoint.sh"]
