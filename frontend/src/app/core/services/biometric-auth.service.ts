import { Injectable, signal } from '@angular/core';

import { TokenStorageService } from './token-storage.service';

interface PasswordCredentialInit {
  id: string;
  password: string;
}

type PasswordCredentialFactory = new (data: PasswordCredentialInit) => Credential;
type PasswordCredentialRequestOptions = CredentialRequestOptions & {
  password: true;
  mediation: 'required';
};
type PlatformAuthenticatorApi = {
  isUserVerifyingPlatformAuthenticatorAvailable?: () => Promise<boolean>;
};

function errorName(error: unknown): string | null {
  if (typeof error !== 'object' || error === null || !('name' in error)) return null;
  return typeof error.name === 'string' ? error.name : null;
}

function isPasswordCredential(value: Credential | null): value is Credential & { password: string } {
  return value !== null && value.type === 'password' && 'password' in value && typeof value.password === 'string';
}

// A senha da credencial gerenciada é o JWT atual: isso reaproveita o Credential Manager
// do navegador sem acrescentar um backend WebAuthn, mas não oferece garantia de dispositivo
// equivalente a uma cerimônia WebAuthn com chave assimétrica.
@Injectable({ providedIn: 'root' })
export class BiometricAuthService {
  readonly isAvailable = signal(false);

  constructor(private readonly tokenStorage: TokenStorageService) {}

  async checkAvailability(): Promise<void> {
    try {
      await this.tokenStorage.init();
      const passwordCredential = (globalThis as typeof globalThis & {
        PasswordCredential?: PasswordCredentialFactory;
      }).PasswordCredential;
      const platformAuthenticator = (globalThis as typeof globalThis & {
        PublicKeyCredential?: PlatformAuthenticatorApi;
      }).PublicKeyCredential;

      if (
        typeof navigator === 'undefined' ||
        !navigator.credentials ||
        typeof navigator.credentials.store !== 'function' ||
        typeof navigator.credentials.get !== 'function' ||
        !passwordCredential ||
        !platformAuthenticator?.isUserVerifyingPlatformAuthenticatorAvailable ||
        this.tokenStorage.isUsingSessionFallback()
      ) {
        this.isAvailable.set(false);
        return;
      }

      this.isAvailable.set(await platformAuthenticator.isUserVerifyingPlatformAuthenticatorAvailable());
    } catch {
      this.isAvailable.set(false);
    }
  }

  async enableForCurrentUser(token: string, userEmail: string): Promise<void> {
    if (!this.isAvailable()) await this.checkAvailability();
    if (!this.isAvailable()) throw new Error('Este dispositivo não oferece login com biometria.');

    const factory = (globalThis as typeof globalThis & { PasswordCredential?: PasswordCredentialFactory }).PasswordCredential;
    if (!factory) throw new Error('O gerenciador de credenciais não está disponível.');
    try {
      await navigator.credentials.store(new factory({ id: userEmail, password: token }));
      await this.tokenStorage.enableBiometric();
    } catch (error: unknown) {
      if (errorName(error) === 'SecurityError') {
        throw new Error('A biometria exige um contexto seguro (HTTPS ou localhost).');
      }
      throw error;
    }
  }

  async authenticate(): Promise<string | null> {
    await this.tokenStorage.init();
    if (!this.tokenStorage.biometricEnabled()) return null;
    if (!this.isAvailable()) await this.checkAvailability();
    if (!this.isAvailable()) throw new Error('Este dispositivo não oferece login com biometria.');

    try {
      const credentials = navigator.credentials as CredentialsContainer & {
        get(options: PasswordCredentialRequestOptions): Promise<Credential | null>;
      };
      const credential = await credentials.get({ password: true, mediation: 'required' });
      if (!isPasswordCredential(credential) || !credential.password) return null;
      await this.tokenStorage.saveToken(credential.password);
      return this.tokenStorage.token();
    } catch (error: unknown) {
      if (errorName(error) === 'NotAllowedError') return null;
      if (errorName(error) === 'SecurityError') {
        throw new Error('A biometria exige um contexto seguro (HTTPS ou localhost).');
      }
      throw error;
    }
  }

  async disableBiometric(): Promise<void> {
    await this.tokenStorage.disableBiometric();
    try {
      if (typeof navigator !== 'undefined') await navigator.credentials?.preventSilentAccess();
    } catch {
      // Nem todos os gerenciadores implementam preventSilentAccess.
    }
  }
}
