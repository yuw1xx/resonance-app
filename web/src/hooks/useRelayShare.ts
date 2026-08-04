import { useCallback, useState } from 'react'
import { useSettingsStore } from '@/stores/settings'
import { getStreamUrl } from '@/api/subsonic'
import { uploadFile, createManifest, mimeForSuffix, type RelaySongEntry } from '@/lib/relay'
import { share } from '@/lib/share'
import { toast } from '@/components/Toast'
import type { QueueSong } from '@/stores/player'

export function useRelayShare() {
  const { relayServerUrl, relayUploadToken } = useSettingsStore()
  const [sharing, setSharing] = useState(false)
  const [progress, setProgress] = useState(0)
  const [total, setTotal] = useState(0)
  const configured = !!relayServerUrl && !!relayUploadToken

  const shareSongs = useCallback(async (songs: QueueSong[], label: string) => {
    if (!configured || !songs.length || sharing) return
    setSharing(true)
    setProgress(0)
    setTotal(songs.length)
    try {
      const entries: RelaySongEntry[] = []
      for (const song of songs) {
        const res = await fetch(getStreamUrl(song.id))
        if (!res.ok) throw new Error(`Couldn't fetch "${song.title}"`)
        const blob = await res.blob()
        const token = await uploadFile(relayServerUrl, relayUploadToken, blob)
        entries.push({
          token,
          title: song.title,
          artist: song.artist,
          mime: blob.type || mimeForSuffix(song.suffix),
          ext: song.suffix ?? 'mp3',
        })
        setProgress(p => p + 1)
      }

      // Share a link to this app's own receive page, not the bare relay URL — the recipient
      // gets a proper play/download page instead of a raw file stream or JSON blob, and the
      // link is self-describing (carries which relay server to query) so it works for
      // whoever opens it regardless of their own Settings.
      const isManifest = entries.length > 1
      const token = isManifest
        ? await createManifest(relayServerUrl, relayUploadToken, entries)
        : entries[0].token
      // BASE_URL already ends in '/' (e.g. '/resonance/app/') and 'relay/...' has no leading
      // slash, so this resolves correctly under a subpath deploy as well as at root ('/').
      const receiveUrl = new URL(`${window.location.origin}${import.meta.env.BASE_URL}relay/${token}`)
      receiveUrl.searchParams.set('server', relayServerUrl)
      receiveUrl.searchParams.set('type', isManifest ? 'manifest' : 'file')

      await share(receiveUrl.toString(), label)
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Couldn\'t share via relay')
    } finally {
      setSharing(false)
    }
  }, [configured, relayServerUrl, relayUploadToken, sharing])

  return { shareSongs, sharing, progress, total, configured }
}
