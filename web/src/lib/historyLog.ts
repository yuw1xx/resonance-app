export interface HistoryEntry {
  songId: string
  title: string
  artist: string
  album: string
  timestampMs: number
  durationMs: number
}

const KEY = 'resonance-history-log'
const MAX_ENTRIES = 10_000

export function readHistoryLog(): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function appendHistoryEntry(entry: HistoryEntry) {
  try {
    const log = readHistoryLog()
    log.push(entry)
    if (log.length > MAX_ENTRIES) log.splice(0, log.length - MAX_ENTRIES)
    localStorage.setItem(KEY, JSON.stringify(log))
  } catch {
    // Storage quota exceeded/unavailable — drop silently, stats just won't include this play.
  }
}

export function clearHistoryLog() {
  try { localStorage.removeItem(KEY) } catch { /* ignore */ }
}

/* ─── Aggregations — all pure functions over the log, computed on demand ─── */

export function getDailyTotals(days: number): { date: string; totalMs: number }[] {
  const log = readHistoryLog()
  const buckets = new Map<string, number>()
  const now = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    buckets.set(d.toISOString().slice(0, 10), 0)
  }
  const cutoff = Date.now() - days * 86_400_000
  for (const e of log) {
    if (e.timestampMs < cutoff) continue
    const key = new Date(e.timestampMs).toISOString().slice(0, 10)
    if (buckets.has(key)) buckets.set(key, (buckets.get(key) ?? 0) + e.durationMs)
  }
  return Array.from(buckets.entries()).map(([date, totalMs]) => ({ date, totalMs }))
}

export function getHourlyDistribution(): { hour: number; count: number }[] {
  const log = readHistoryLog()
  const buckets = Array.from({ length: 24 }, (_, hour) => ({ hour, count: 0 }))
  for (const e of log) {
    const hour = new Date(e.timestampMs).getHours()
    buckets[hour].count++
  }
  return buckets
}

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

export function getDayOfWeekDistribution(): { day: string; count: number }[] {
  const log = readHistoryLog()
  const buckets = DAY_LABELS.map(day => ({ day, count: 0 }))
  for (const e of log) {
    const day = new Date(e.timestampMs).getDay()
    buckets[day].count++
  }
  return buckets
}

export function getTopSongs(limit = 10) {
  const log = readHistoryLog()
  const byId = new Map<string, { songId: string; title: string; artist: string; album: string; count: number }>()
  for (const e of log) {
    const existing = byId.get(e.songId)
    if (existing) existing.count++
    else byId.set(e.songId, { songId: e.songId, title: e.title, artist: e.artist, album: e.album, count: 1 })
  }
  return Array.from(byId.values()).sort((a, b) => b.count - a.count).slice(0, limit)
}

export function getTopArtists(limit = 10) {
  const log = readHistoryLog()
  const byArtist = new Map<string, number>()
  for (const e of log) {
    if (!e.artist) continue
    byArtist.set(e.artist, (byArtist.get(e.artist) ?? 0) + 1)
  }
  return Array.from(byArtist.entries())
    .map(([artist, count]) => ({ artist, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, limit)
}

export function getStreaks(): { current: number; longest: number } {
  const log = readHistoryLog()
  if (!log.length) return { current: 0, longest: 0 }

  const dates = new Set(log.map(e => new Date(e.timestampMs).toISOString().slice(0, 10)))
  const sorted = Array.from(dates).sort()

  let longest = 1
  let run = 1
  for (let i = 1; i < sorted.length; i++) {
    const prev = new Date(sorted[i - 1])
    const cur = new Date(sorted[i])
    const diffDays = Math.round((cur.getTime() - prev.getTime()) / 86_400_000)
    if (diffDays === 1) { run++; longest = Math.max(longest, run) }
    else run = 1
  }

  // Current streak: walk backward from today (or yesterday, if nothing logged today yet).
  const todayKey = new Date().toISOString().slice(0, 10)
  const cursor = new Date()
  if (!dates.has(todayKey)) cursor.setDate(cursor.getDate() - 1)
  let current = 0
  while (dates.has(cursor.toISOString().slice(0, 10))) {
    current++
    cursor.setDate(cursor.getDate() - 1)
  }

  return { current, longest }
}

export function getTotalListenMs(): number {
  return readHistoryLog().reduce((sum, e) => sum + e.durationMs, 0)
}
