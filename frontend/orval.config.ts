import { defineConfig } from 'orval';

export default defineConfig({
  fintechApi: {
    input: '../api-spec/openapi.yaml',
    output: {
      mode: 'tags-split',
      target: 'src/app/core/api',
      client: 'angular',
      override: {
        angular: {
          provideIn: 'root',
        },
      },
    },
  },
});
