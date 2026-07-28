#!/bin/sh
set -e
# ponytail: 运行时用 EG_API_KEY（compose environment 注入，可靠）生成前端 config.js，
# 替代不可靠的 compose build args（同 PIP_INDEX_URL 前车之鉴）。缺省回退 changeme 不影响本地默认值
envsubst '${EG_API_KEY}' < /etc/nginx/config.js.template > /usr/share/nginx/html/config.js
exec nginx -g 'daemon off;'
