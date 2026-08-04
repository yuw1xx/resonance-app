import { useCallback, useState } from 'react'
import { useSettingsStore } from '@/stores/settings'
import { downloadSong, isDownloaded } from '@/lib/offlineDownloads'
import { toast } from '@/components/Toast'
import type { QueueSong } from '@/stores/player'

export function useDownloadSongs(songs: QueueSong[]) {
  const { maxBitRate, replayGain } = useSettingsStore()
  const [downloading, setDownloading] = useState(false)
  const [progress, setProgress] = useState(0)
  const allDownloaded = songs.length > 0 && songs.every(s => isDownloaded(s.id))

  const download = useCallback(async () => {
    if (downloading || !songs.length) return
    setDownloading(true)
    setProgress(0)
    let done = 0
    let failed = 0
    for (const song of songs) {
      if (!isDownloaded(song.id)) {
        try {
          await downloadSong(song, maxBitRate, replayGain)
        } catch {
          failed++
        }
      }
      done++
      setProgress(done)
    }
    setDownloading(false)
    if (failed > 0) toast(`Downloaded ${songs.length - failed} of ${songs.length} — ${failed} failed`)
    else toast(songs.length > 1 ? `Downloaded ${songs.length} songs` : 'Downloaded for offline playback')
  }, [songs, downloading, maxBitRate, replayGain])

  return { download, downloading, progress, total: songs.length, allDownloaded }
}
