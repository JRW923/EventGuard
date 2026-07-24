FROM node:20-alpine AS build
WORKDIR /app
ARG VITE_API_KEY=changeme
ENV VITE_API_KEY=$VITE_API_KEY
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# ponytail: 覆盖默认配置，使生产镜像代理 API/WS 到后端（vite proxy 仅开发期有效）
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
