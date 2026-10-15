import { describe, expect, it, vi } from 'vitest';
import { BiometricAuthService } from './biometric-auth.service';
import { TokenStorageService } from './token-storage.service';

describe('BiometricAuthService', () => {
  it('desabilita biometria quando o storage persistente não está disponível', async () => {
    vi.stubGlobal('indexedDB', undefined);
    const storage = new TokenStorageService();
    const service = new BiometricAuthService(storage);

    await service.checkAvailability();

    expect(storage.isUsingSessionFallback()).toBe(true);
    expect(service.isAvailable()).toBe(false);
    vi.unstubAllGlobals();
  });
});
