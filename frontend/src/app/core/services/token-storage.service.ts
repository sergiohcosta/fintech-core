import { Injectable, signal } from '@angular/core';

type StoreName = 'tokens' | 'keys';

const DATABASE_NAME = 'fintech-core-auth';
const DATABASE_VERSION = 1;
const TOKEN_STORE = 'tokens';
const KEY_STORE = 'keys';
const CURRENT_TOKEN_KEY = 'current';
const BIOMETRIC_FLAG_KEY = 'biometric-enabled';
const MASTER_KEY = 'master';
const LEGACY_TOKEN_KEY = 'auth_token';
const FALLBACK_TOKEN_KEY = 'fintech-core-auth-token';
const FALLBACK_BIOMETRIC_KEY = 'fintech-core-biometric-enabled';

// AES-GCM reduces exposure of a copied IndexedDB dump, while the in-memory signal keeps
// the interceptor synchronous. The key stays on-device; this is pragmatic at-rest protection,
// not a substitute for XSS prevention or a server-backed WebAuthn credential.
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  readonly token = signal<string | null>(null);
  readonly biometricEnabled = signal(false);
  readonly isUsingSessionFallback = signal(false);
  readonly storageWarning = signal<string | null>(null);

  private database: IDBDatabase | null = null;
  private cryptoKey: CryptoKey | null = null;
  private mode: 'indexeddb' | 'session' | null = null;
  private initialization: Promise<void> | null = null;

  init(): Promise<void> {
    this.initialization ??= this.initialize();
    return this.initialization;
  }

  async saveToken(token: string): Promise<void> {
    await this.init();
    if (this.mode === 'indexeddb' && this.cryptoKey) {
      try {
        await this.putRecord(TOKEN_STORE, CURRENT_TOKEN_KEY, await this.encrypt(token));
        this.token.set(token);
        this.removeLegacyToken();
        return;
      } catch {
        this.activateSessionFallback();
      }
    }
    this.saveSessionToken(token);
  }

  async clearToken(): Promise<void> {
    this.token.set(null);
    await this.init();
    this.token.set(null);
    this.removeLegacyToken();
    try {
      if (this.mode === 'indexeddb') await this.deleteRecord(TOKEN_STORE, CURRENT_TOKEN_KEY);
      this.removeSessionValue(FALLBACK_TOKEN_KEY);
    } catch {
      this.activateSessionFallback();
      this.removeSessionValue(FALLBACK_TOKEN_KEY);
    }
  }

  async enableBiometric(): Promise<void> {
    await this.init();
    this.biometricEnabled.set(true);
    try {
      if (this.mode === 'indexeddb') {
        await this.putRecord(TOKEN_STORE, BIOMETRIC_FLAG_KEY, true);
      } else {
        this.writeSessionValue(FALLBACK_BIOMETRIC_KEY, 'true');
      }
    } catch {
      this.activateSessionFallback();
      this.writeSessionValue(FALLBACK_BIOMETRIC_KEY, 'true');
    }
  }

  async disableBiometric(): Promise<void> {
    await this.init();
    this.biometricEnabled.set(false);
    this.removeSessionValue(FALLBACK_BIOMETRIC_KEY);
    try {
      if (this.mode === 'indexeddb') await this.deleteRecord(TOKEN_STORE, BIOMETRIC_FLAG_KEY);
    } catch {
      this.activateSessionFallback();
    }
  }

  private async initialize(): Promise<void> {
    try {
      if (typeof indexedDB === 'undefined' || !globalThis.crypto?.subtle) {
        throw new Error('IndexedDB ou Web Crypto indisponível.');
      }
      this.database = await this.openDatabase();
      this.cryptoKey = await this.loadOrCreateKey();
      this.mode = 'indexeddb';

      const [storedToken, storedBiometricFlag] = await Promise.all([
        this.getRecord(TOKEN_STORE, CURRENT_TOKEN_KEY),
        this.getRecord(TOKEN_STORE, BIOMETRIC_FLAG_KEY),
      ]);
      this.biometricEnabled.set(storedBiometricFlag === true);

      const legacyToken = this.readLegacyToken();
      const sessionToken = this.readSessionValue(FALLBACK_TOKEN_KEY);
      if (typeof storedToken === 'string') {
        this.token.set(await this.decrypt(storedToken));
      } else if (legacyToken || sessionToken) {
        const token = legacyToken ?? sessionToken!;
        await this.putRecord(TOKEN_STORE, CURRENT_TOKEN_KEY, await this.encrypt(token));
        this.token.set(token);
      }

      if (legacyToken) this.removeLegacyToken();
      this.removeSessionValue(FALLBACK_TOKEN_KEY);
      this.removeSessionValue(FALLBACK_BIOMETRIC_KEY);
    } catch {
      this.activateSessionFallback();
      this.restoreSessionFallback();
    }
  }

  private activateSessionFallback(): void {
    this.mode = 'session';
    this.cryptoKey = null;
    this.isUsingSessionFallback.set(true);
    this.storageWarning.set('Armazenamento persistente indisponível; a sessão ficará salva somente nesta aba.');
    this.database?.close();
    this.database = null;
  }

  private restoreSessionFallback(): void {
    const sessionToken = this.readSessionValue(FALLBACK_TOKEN_KEY);
    const legacyToken = this.readLegacyToken();
    const token = sessionToken ?? legacyToken;
    if (token) {
      this.saveSessionToken(token);
      if (legacyToken) this.removeLegacyToken();
    }
    this.biometricEnabled.set(this.readSessionValue(FALLBACK_BIOMETRIC_KEY) === 'true');
  }

  private saveSessionToken(token: string): void {
    this.writeSessionValue(FALLBACK_TOKEN_KEY, token);
    this.token.set(token);
  }

  private async openDatabase(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
      request.onupgradeneeded = () => {
        const database = request.result;
        if (!database.objectStoreNames.contains(TOKEN_STORE)) database.createObjectStore(TOKEN_STORE);
        if (!database.objectStoreNames.contains(KEY_STORE)) database.createObjectStore(KEY_STORE);
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error('Não foi possível abrir o IndexedDB.'));
      request.onblocked = () => reject(new Error('A atualização do IndexedDB foi bloqueada.'));
    });
  }

  private async loadOrCreateKey(): Promise<CryptoKey> {
    const stored = await this.getRecord(KEY_STORE, MASTER_KEY);
    if (stored instanceof Uint8Array) {
      return crypto.subtle.importKey('raw', this.toOwnedBuffer(stored), { name: 'AES-GCM' }, true, ['encrypt', 'decrypt']);
    }
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
    const rawKey = new Uint8Array(await crypto.subtle.exportKey('raw', key));
    await this.putRecord(KEY_STORE, MASTER_KEY, rawKey);
    return key;
  }

  private async encrypt(token: string): Promise<string> {
    if (!this.cryptoKey) throw new Error('Chave de criptografia indisponível.');
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      this.cryptoKey,
      new TextEncoder().encode(token),
    );
    return `${this.toBase64(iv)}.${this.toBase64(new Uint8Array(ciphertext))}`;
  }

  private async decrypt(envelope: string): Promise<string> {
    if (!this.cryptoKey) throw new Error('Chave de criptografia indisponível.');
    const [ivPart, ciphertextPart, extra] = envelope.split('.');
    if (!ivPart || !ciphertextPart || extra !== undefined) throw new Error('Envelope de token inválido.');
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: this.toOwnedBuffer(this.fromBase64(ivPart)) },
      this.cryptoKey,
      this.toOwnedBuffer(this.fromBase64(ciphertextPart)),
    );
    return new TextDecoder().decode(plaintext);
  }

  private toBase64(bytes: Uint8Array): string {
    let binary = '';
    bytes.forEach(byte => { binary += String.fromCharCode(byte); });
    return btoa(binary);
  }

  private fromBase64(value: string): Uint8Array {
    const binary = atob(value);
    return Uint8Array.from(binary, character => character.charCodeAt(0));
  }

  private toOwnedBuffer(bytes: Uint8Array): ArrayBuffer {
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(bytes);
    return copy.buffer;
  }

  private getRecord(storeName: StoreName, key: string): Promise<unknown> {
    const database = this.database;
    if (!database) return Promise.reject(new Error('IndexedDB indisponível.'));
    return new Promise((resolve, reject) => {
      const transaction = database.transaction(storeName, 'readonly');
      const request = transaction.objectStore(storeName).get(key);
      request.onsuccess = () => resolve(request.result as unknown);
      request.onerror = () => reject(request.error ?? new Error('Falha ao ler IndexedDB.'));
      transaction.onabort = () => reject(transaction.error ?? new Error('Leitura do IndexedDB cancelada.'));
    });
  }

  private putRecord(storeName: StoreName, key: string, value: unknown): Promise<void> {
    const database = this.database;
    if (!database) return Promise.reject(new Error('IndexedDB indisponível.'));
    return new Promise((resolve, reject) => {
      const transaction = database.transaction(storeName, 'readwrite');
      transaction.objectStore(storeName).put(value, key);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error ?? new Error('Falha ao gravar IndexedDB.'));
      transaction.onabort = () => reject(transaction.error ?? new Error('Gravação do IndexedDB cancelada.'));
    });
  }

  private deleteRecord(storeName: StoreName, key: string): Promise<void> {
    const database = this.database;
    if (!database) return Promise.reject(new Error('IndexedDB indisponível.'));
    return new Promise((resolve, reject) => {
      const transaction = database.transaction(storeName, 'readwrite');
      transaction.objectStore(storeName).delete(key);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error ?? new Error('Falha ao remover do IndexedDB.'));
      transaction.onabort = () => reject(transaction.error ?? new Error('Remoção do IndexedDB cancelada.'));
    });
  }

  private readLegacyToken(): string | null {
    try { return typeof localStorage === 'undefined' ? null : localStorage.getItem(LEGACY_TOKEN_KEY); }
    catch { return null; }
  }

  private removeLegacyToken(): void {
    try { localStorage.removeItem(LEGACY_TOKEN_KEY); } catch { /* Storage pode estar bloqueado. */ }
  }

  private safeSessionStorage(): Storage | null {
    try { return typeof sessionStorage === 'undefined' ? null : sessionStorage; }
    catch { return null; }
  }

  private readSessionValue(key: string): string | null {
    try { return this.safeSessionStorage()?.getItem(key) ?? null; }
    catch { return null; }
  }

  private writeSessionValue(key: string, value: string): void {
    try {
      this.safeSessionStorage()?.setItem(key, value);
    } catch {
      this.storageWarning.set('O armazenamento da aba também está bloqueado; a sessão não será lembrada ao recarregar.');
    }
  }

  private removeSessionValue(key: string): void {
    try { this.safeSessionStorage()?.removeItem(key); } catch { /* Storage pode estar bloqueado. */ }
  }
}
