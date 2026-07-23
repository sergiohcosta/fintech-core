#!/bin/sh
# Reescreve env.js com a config de runtime deste ambiente, no boot do container.
# O nginx:alpine executa qualquer *.sh de /docker-entrypoint.d/ antes de subir.
#
# De onde vem cada valor:
#   APP_ENVIRONMENT / APP_VERSION -> injetados no deploy (kubectl set env)
#   APP_SHA                       -> assado no build (Dockerfile ARG)
# Defaults mantêm o app válido mesmo sem nada setado (ex: `docker run` cru).
set -eu

: "${APP_ENVIRONMENT:=local}"
: "${APP_VERSION:=}"
: "${APP_SHA:=dev}"

cat > /usr/share/nginx/html/env.js <<EOF
window.__APP_ENV = { environment: "${APP_ENVIRONMENT}", version: "${APP_VERSION}", sha: "${APP_SHA}" };
EOF

echo "app-env: environment=${APP_ENVIRONMENT} version=${APP_VERSION:-<none>} sha=${APP_SHA}"
