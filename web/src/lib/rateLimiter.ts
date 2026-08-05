/** Serializes calls to a rate-limited external API (MusicBrainz, Discogs) behind a minimum
 * interval between requests. A single in-flight promise chain both prevents concurrent callers
 * from racing ahead of each other and gives one serialization point where the delay is enforced. */
export function createRateLimiter(minIntervalMs: number) {
  let chain = Promise.resolve()
  let lastCallAt = 0

  return function withRateLimit<T>(fn: () => Promise<T>): Promise<T> {
    const result = chain.then(async () => {
      const wait = minIntervalMs - (Date.now() - lastCallAt)
      if (wait > 0) await new Promise(r => setTimeout(r, wait))
      try {
        return await fn()
      } finally {
        lastCallAt = Date.now()
      }
    })
    chain = result.then(() => undefined, () => undefined)
    return result
  }
}
