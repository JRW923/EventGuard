FROM node:20-alpine AS build
WORKDIR /app
# ponytail: 构建期占位，生产须传真实密钥（与 EG_API_KEY 一致）；默认 changeme 仅本地兜底
ARG VITE_API_KEY=changeme
ENV VITE_API_KEY=$VITE_API_KEY
COPY package.json package-lock.json* ./
# ponytail: 国内部署写死 npmmirror，避免 npmjs.org 超时（同 AI 镜像写死思路）
RUN npm install --registry=https://registry.npmmirror.com
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# ponytail: 覆盖默认配置，使生产镜像代理 API/WS 到后端（vite proxy 仅开发期有效）
COPY nginx.conf /etc/nginx/conf.d/default.conf
# ponytail: 运行时注入 API Key（见 nginx-entrypoint.sh），不再依赖 build args
COPY config.js.template /etc/nginx/config.js.template
COPY nginx-entrypoint.sh /nginx-entrypoint.sh
RUN chmod +x /nginx-entrypoint.sh
EXPOSE 80
ENTRYPOINT ["/nginx-entrypoint.sh"]
