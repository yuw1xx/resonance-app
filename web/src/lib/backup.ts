import { useSettingsStore } from '@/stores/settings'
import { readHistoryLog, type HistoryEntry } from '@/lib/historyLog'
import { getOfflineIndex, type OfflineEntry } from '@/lib/offlineDownloads'

const HISTORY_KEY = 'resonance-history-log'
const OFFLINE_INDEX_KEY = 'resonance-offline-index'

export interface BackupFile {
  version: 1
  exportedAt: number
  settings: unknown
  historyLog: HistoryEntry[]
  offlineDownloadsIndex: OfflineEntry[]
}

function buildBackup(): BackupFile {
  return {
    version: 1,
    exportedAt: Date.now(),
    // Includes credential fields (Discogs/relay tokens, etc.) so a restore is complete —
    // the exported file should be treated as sensitive, same as any of those tokens alone.
    settings: useSettingsStore.getState(),
    historyLog: readHistoryLog(),
    offlineDownloadsIndex: getOfflineIndex(),
  }
}

export function downloadBackup() {
  const backup = buildBackup()
  const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `resonance-backup-${new Date().toISOString().slice(0, 10)}.json`
  a.click()
  URL.revokeObjectURL(url)
}

export async function importBackupFile(file: File): Promise<void> {
  const text = await file.text()
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('That file isn\'t valid JSON')
  }
  if (!parsed || typeof parsed !== 'object' || (parsed as BackupFile).version !== 1) {
    throw new Error('This doesn\'t look like a Resonance backup file')
  }
  const backup = parsed as BackupFile
  if (backup.settings && typeof backup.settings === 'object') {
    useSettingsStore.setState(backup.settings as Partial<ReturnType<typeof useSettingsStore.getState>>)
  }
  if (Array.isArray(backup.historyLog)) {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(backup.historyLog))
  }
  if (Array.isArray(backup.offlineDownloadsIndex)) {
    // Restores which songs were marked downloaded, not the cached audio itself — those
    // still need re-downloading on this device/browser before they're playable offline.
    localStorage.setItem(OFFLINE_INDEX_KEY, JSON.stringify(backup.offlineDownloadsIndex))
  }
}
