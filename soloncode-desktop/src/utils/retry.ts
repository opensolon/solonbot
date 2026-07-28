export async function withRetry<T>(
  operation: () => Promise<T>,
  delaysMs: readonly number[] = [100, 300, 700],
): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= delaysMs.length; attempt++) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      if (attempt >= delaysMs.length) break;
      await new Promise(resolve => setTimeout(resolve, delaysMs[attempt]));
    }
  }
  throw lastError;
}
