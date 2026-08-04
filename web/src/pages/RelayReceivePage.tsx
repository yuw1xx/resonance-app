import { useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getManifest, fileUrl, type RelaySongEntry } from '@/lib/relay'
import { Icon } from '@/components/Icon'

function SongEntryRow({ serverUrl, entry }: { serverUrl: string; entry: RelaySongEntry }) {
  const [consumed, setConsumed] = useState(false)
  const url = fileUrl(serverUrl, entry.token)

  return (
    <div className="flex items-center gap-3 px-4 py-3 rounded-2xl bg-surface-c">
      <div className="w-10 h-10 rounded-lg bg-surface-high flex items-center justify-center flex-shrink-0">
        <Icon name="music_note" size={18} className="text-on-surface-var" />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[14px] font-[500] text-on-surface truncate">{entry.title}</p>
        <p className="text-[12px] text-on-surface-var truncate">{entry.artist}</p>
      </div>
      {consumed ? (
        <span className="text-[11px] text-outline flex-shrink-0">Link used</span>
      ) : (
        <a
          href={url}
          download
          onClick={() => setConsumed(true)}
          className="flex items-center gap-1.5 px-3 py-2 rounded-full bg-primary text-on-primary
            text-[12px] font-[600] flex-shrink-0 hover:brightness-105 transition-all duration-150"
        >
          <Icon name="download" size={14} />
          Download
        </a>
      )}
    </div>
  )
}

function SingleFileReceiver({ serverUrl, token }: { serverUrl: string; token: string }) {
  const [revealed, setRevealed] = useState(false)
  const url = fileUrl(serverUrl, token)

  return (
    <div className="bg-surface-c rounded-2xl p-6 text-center">
      <Icon name="music_note" size={40} className="text-primary mx-auto mb-3" />
      <p className="text-[14px] text-on-surface-var mb-5">
        Someone shared a song with you. This link works <strong className="text-on-surface">once</strong> —
        playing or downloading it will use it up.
      </p>
      {!revealed ? (
        <button
          onClick={() => setRevealed(true)}
          className="px-6 py-3 rounded-full bg-primary text-on-primary text-[14px] font-[600]
            hover:brightness-105 transition-all duration-150"
        >
          Reveal song
        </button>
      ) : (
        <div className="space-y-4">
          <audio controls autoPlay src={url} className="w-full" />
          <a
            href={url}
            download
            className="inline-flex items-center gap-1.5 text-[13px] font-[600] text-primary hover:text-primary/80 transition-colors duration-150"
          >
            <Icon name="download" size={14} />
            Download instead
          </a>
        </div>
      )}
    </div>
  )
}

function ManifestReceiver({ serverUrl, token }: { serverUrl: string; token: string }) {
  const { data: songs, isLoading, isError } = useQuery({
    queryKey: ['relay-manifest', serverUrl, token],
    queryFn: () => getManifest(serverUrl, token),
    staleTime: 0,
    retry: false,
  })

  if (isLoading) {
    return <div className="w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin mx-auto" />
  }
  if (isError || !songs) {
    return <p className="text-center text-[13px] text-on-surface-var">This link has expired or doesn't exist.</p>
  }

  return (
    <div className="space-y-2">
      <p className="text-[13px] text-on-surface-var mb-3">
        {songs.length} {songs.length === 1 ? 'song' : 'songs'} shared with you — each download link works once.
      </p>
      {songs.map((entry, i) => (
        <SongEntryRow key={`${entry.token}-${i}`} serverUrl={serverUrl} entry={entry} />
      ))}
    </div>
  )
}

export function RelayReceivePage() {
  const { token } = useParams<{ token: string }>()
  const [searchParams] = useSearchParams()
  const serverUrl = searchParams.get('server')
  const type = searchParams.get('type') === 'manifest' ? 'manifest' : 'file'

  return (
    <div className="min-h-screen bg-md-bg flex items-center justify-center p-4">
      <div className="w-full max-w-[420px]">
        <div className="text-center mb-6">
          <div className="w-12 h-12 rounded-2xl bg-primary-container flex items-center justify-center mx-auto mb-3">
            <Icon name="ios_share" size={22} className="text-on-primary-container" />
          </div>
          <h1 className="text-[20px] font-[700] text-on-surface tracking-[-0.3px]">Shared from Resonance</h1>
        </div>

        {!token || !serverUrl ? (
          <p className="text-center text-[13px] text-on-surface-var">This link is missing information and can't be opened.</p>
        ) : type === 'manifest' ? (
          <ManifestReceiver serverUrl={serverUrl} token={token} />
        ) : (
          <SingleFileReceiver serverUrl={serverUrl} token={token} />
        )}
      </div>
    </div>
  )
}
