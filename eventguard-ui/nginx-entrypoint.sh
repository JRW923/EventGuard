#!/bin/sh
set -e
# 登录态改为前端 JWT（localStorage + Authorization 头），无需运行时注入密钥
exec nginx -g 'daemon off;'
