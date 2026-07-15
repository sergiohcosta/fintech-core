// Config de runtime do frontend. Estes são os defaults de desenvolvimento local
// (npm start). Em container, o entrypoint do nginx (docker-entrypoint.d/40-app-env.sh)
// SOBRESCREVE este arquivo no boot com os valores reais do ambiente (env vars).
// Por que runtime e não build-time: a MESMA imagem roda em dev/hmg/prod.
window.__APP_ENV = { environment: 'local', version: '', sha: 'dev' };
