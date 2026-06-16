/**
 * Extracts error message from an HTTP error response body,
 * falling back to a default string when no backend message exists.
 */
export function extractErrorMessage(
  err: { error?: { message?: string } | null },
  fallback: string,
): string {
  return err.error?.message || fallback;
}
