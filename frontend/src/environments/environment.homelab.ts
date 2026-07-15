export const environment = {
  production: true,
  apiUrl: '', // Requisições relativas. O proxy reverso (Nginx/Ingress) fará o roteamento para o backend.
  devCredentials: null as { email: string; password: string } | null
};
