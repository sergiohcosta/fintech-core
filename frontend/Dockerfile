# Stage 1: Compilação da aplicação Angular
FROM node:20-alpine AS builder
WORKDIR /workspace

# Copia os arquivos de definição de dependências primeiro para aproveitar o cache do Docker
COPY frontend/package.json frontend/package-lock.json ./frontend/
WORKDIR /workspace/frontend
RUN npm ci --quiet

# Copia o restante do código-fonte do frontend
COPY frontend/ ./

# Executa o build de produção do Angular configurado para homelab (requisições relativas)
RUN npx ng build --configuration=homelab

# Stage 2: Imagem final leve com Nginx Alpine
FROM nginx:alpine

# #246 (residual, pós-bump Spring/Jackson/tomcat): Trivy apontou 3 HIGH no Alpine
# baked-in do nginx:alpine (libexpat CVE-2026-93990, pcre2 CVE-2026-103111, tiff
# CVE-2026-4775). Pinar a tag não resolve — todas as tags nginx:alpine* foram
# rebuildadas em 2026-10-14 sobre alpine 3.24.2 com os packages vulneráveis. O
# `apk upgrade --no-cache` puxa as versões patched (libexpat 2.8.5-r0, pcre2
# 10.49-r0, tiff 4.7.2-r0) sem trocar a base. Custo: 1 layer de poucos MB.
RUN apk upgrade --no-cache

# Remove a configuração padrão do Nginx e adiciona a nossa customizada para SPA
RUN rm /etc/nginx/conf.d/default.conf
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copia os arquivos estáticos gerados no Stage 1 para o diretório padrão do Nginx
COPY --from=builder /workspace/frontend/dist/frontend/browser /usr/share/nginx/html

# Config de runtime (marca d'água versão/ambiente): o SHA é assado no build via
# ARG; ambiente e versão SemVer são injetados no deploy (env vars). O script em
# /docker-entrypoint.d/ reescreve env.js no boot — mecanismo nativo do nginx:alpine.
ARG APP_SHA=dev
ENV APP_SHA=$APP_SHA
COPY frontend/docker-entrypoint.d/40-app-env.sh /docker-entrypoint.d/40-app-env.sh
RUN chmod +x /docker-entrypoint.d/40-app-env.sh

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
