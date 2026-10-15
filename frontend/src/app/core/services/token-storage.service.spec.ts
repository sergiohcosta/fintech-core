import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TokenStorageService } from './token-storage.service';

describe('TokenStorageService fallback', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    vi.stubGlobal('indexedDB', undefined);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('migra a chave antiga para sessionStorage e remove localStorage', async () => {
    localStorage.setItem('auth_token', 'jwt-legado');
    const storage = new TokenStorageService();

    await storage.init();

    expect(storage.token()).toBe('jwt-legado');
    expect(storage.isUsingSessionFallback()).toBe(true);
    expect(storage.storageWarning()).not.toBeNull();
    expect(localStorage.getItem('auth_token')).toBeNull();

    await storage.clearToken();
    expect(storage.token()).toBeNull();
  });

  it('mantém a flag biométrica enquanto remove o token de sessão', async () => {
    const storage = new TokenStorageService();
    await storage.init();
    await storage.enableBiometric();
    await storage.saveToken('jwt-atual');
    await storage.clearToken();

    expect(storage.token()).toBeNull();
    expect(storage.biometricEnabled()).toBe(true);

    await storage.disableBiometric();
    expect(storage.biometricEnabled()).toBe(false);
  });
});
