import { Brand } from "./brand.enum";

// Modelo de Erro (para tratar o 400 e 404 que fizemos no Java)
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  details?: Record<string, string>;
}

// O Espelho do Java (DTO de Resposta)
export interface CreditCardModel {
  id: string;
  name: string;
  brand: Brand;
  color: string;
  lastFourDigits: string;
  limitAmount: number;
  closingDay: number;
  dueDay: number;
}

// O Espelho do Java (DTO de Criação - sem ID)
export type CreditCardCreate = Omit<CreditCardModel, 'id'>;