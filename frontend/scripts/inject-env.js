const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const targetPath = path.join(__dirname, '..', 'dist', 'frontend', 'browser', 'env.js');

if (!fs.existsSync(targetPath)) {
  process.exit(0);
}

// 1. SHA
let sha = process.env.CF_PAGES_COMMIT_SHA || process.env.GITHUB_SHA || '';
if (!sha) {
  try {
    sha = execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim();
  } catch {
    sha = 'dev';
  }
}

// 2. Commit Time (ISO 8601)
let commitTime = '';
try {
  commitTime = execSync('git log -1 --format=%cI', { encoding: 'utf8' }).trim();
} catch {}

// 3. Versão SemVer (se houver tag apontando pro commit)
let version = process.env.APP_VERSION || '';
if (!version) {
  try {
    version = execSync('git describe --tags --exact-match 2>/dev/null', { encoding: 'utf8' }).trim();
  } catch {}
}

// 4. Nome do Ambiente
let environment = process.env.APP_ENVIRONMENT || '';
if (!environment) {
  const branch = process.env.CF_PAGES_BRANCH || '';
  if (branch === 'main') {
    environment = 'prod';
  } else if (branch) {
    environment = branch;
  } else if (process.env.CF_PAGES) {
    environment = 'preview';
  } else {
    environment = 'prod';
  }
}

const content = `window.__APP_ENV = { environment: "${environment}", version: "${version}", sha: "${sha}", commitTime: "${commitTime}" };\n`;

fs.writeFileSync(targetPath, content, 'utf8');
console.log(`[inject-env] Generated env.js: environment=${environment} version=${version || '<none>'} sha=${sha.slice(0, 7)} commitTime=${commitTime || '<none>'}`);
