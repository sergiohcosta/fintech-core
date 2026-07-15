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
