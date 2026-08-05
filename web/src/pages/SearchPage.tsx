import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { SongRow } from '@/components/SongRow'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { SelectionToolbar } from '@/components/SelectionToolbar'
import { useSelection } from '@/hooks/useSelection'
import { usePlayerStore, type QueueSong } from '@/stores/player'

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}

export function SearchPage() {
  const navigate = useNavigate()
  const play = usePlayerStore(s => s.play)
  const [query, setQuery] = useState('')
  const dq = useDebounce(query, 300)
  const selection = useSelection()
  const [selectMode, setSelectMode] = useState(false)

  const { data, isFetching } = useQuery({
    queryKey: ['search', dq],
    queryFn: () => subsonic.search(dq),
    enabled: dq.length >= 2,
    staleTime: 30_000,
  })

  const songs: QueueSong[] = (data?.song ?? []).map(s => ({
    ...s,
    title: s.title,
    artist: s.artist ?? 'Unknown Artist',
    album: s.album ?? '',
  }))

  const hasResults = (data?.song?.length ?? 0) + (data?.album?.length ?? 0) + (data?.artist?.length ?? 0) > 0

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      {/* Search bar */}
      <div className="sticky top-0 z-10 px-6 pt-6 pb-4 bg-md-bg/80 backdrop-blur-md">
        <div className="relative max-w-xl">
          <div className="absolute left-4 top-1/2 -translate-y-1/2 pointer-events-none">
            {isFetching
              ? <div className="w-4 h-4 border-2 border-primary border-t-transparent rounded-full animate-spin" />
              : <Icon name="search" size={20} className="text-on-surface-var" />
            }
          </div>
          <input
            type="search"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Songs, albums, artists…"
            autoFocus
            className="w-full bg-surface-c border border-outline-var/30 rounded-2xl
              pl-12 pr-4 py-3.5 text-[14px] text-on-surface placeholder-outline
              focus:border-primary focus:ring-2 focus:ring-primary/15 focus:bg-surface-high
              transition-all duration-200 ease-md-standard"
          />
          {query && (
            <button
              onClick={() => setQuery('')}
              className="absolute right-3 top-1/2 -translate-y-1/2 w-7 h-7 rounded-full
                flex items-center justify-center text-on-surface-var hover:bg-on-surface/8
                transition-colors duration-150"
            >
              <Icon name="close" size={16} />
            </button>
          )}
        </div>
      </div>

      <div className="px-6 pb-8 max-w-xl space-y-8">
        {!query && (
          <div className="flex flex-col items-center justify-center py-20 gap-3 text-on-surface-var">
            <Icon name="search" size={48} filled={false} className="opacity-20" />
            <p className="text-[14px]">Search your library</p>
          </div>
        )}

        {query.length >= 2 && !isFetching && !hasResults && (
          <div className="flex flex-col items-center justify-center py-16 gap-2 text-on-surface-var">
            <Icon name="sentiment_dissatisfied" size={40} filled={false} className="opacity-30" />
            <p className="text-[14px]">No results for <strong className="text-on-surface">"{query}"</strong></p>
          </div>
        )}

        {/* Artists */}
        {(data?.artist?.length ?? 0) > 0 && (
          <Section label="Artists">
            <div className="space-y-1">
              {data!.artist!.map(artist => (
                <ResultRow
                  key={artist.id}
                  coverArt={artist.coverArt}
                  title={artist.name}
                  subtitle={artist.albumCount != null ? `${artist.albumCount} albums` : undefined}
                  coverRound
                  onClick={() => navigate(`/artists/${artist.id}`)}
                />
              ))}
            </div>
          </Section>
        )}

        {/* Albums */}
        {(data?.album?.length ?? 0) > 0 && (
          <Section label="Albums">
            <div className="space-y-1">
              {data!.album!.map(album => (
                <ResultRow
                  key={album.id}
                  coverArt={album.coverArt}
                  title={album.name}
                  subtitle={album.artist}
                  onClick={() => navigate(`/albums/${album.id}`)}
                />
              ))}
            </div>
          </Section>
        )}

        {/* Songs */}
        {songs.length > 0 && (
          <Section label="Songs" action={
            selectMode ? (
              <button
                onClick={() => { selection.clear(); setSelectMode(false) }}
                className="text-[12px] text-on-surface-var hover:text-on-surface font-[500]
                  transition-colors duration-150"
              >
                Cancel
              </button>
            ) : (
              <div className="flex items-center gap-3">
                <button
                  onClick={() => setSelectMode(true)}
                  className="text-[12px] text-on-surface-var hover:text-on-surface font-[500]
                    transition-colors duration-150"
                >
                  Select
                </button>
                <button
                  onClick={() => play(songs, 0)}
                  className="text-[12px] text-primary hover:text-on-primary-container font-[500]
                    transition-colors duration-150"
                >
                  Play all
                </button>
              </div>
            )
          }>
            <AnimatePresence>
              {selectMode && (
                <SelectionToolbar
                  key="selection-toolbar"
                  selectedIds={selection.selected}
                  songs={songs}
                  onClear={() => { selection.clear(); setSelectMode(false) }}
                />
              )}
            </AnimatePresence>
            <div className="bg-surface-c rounded-2xl overflow-hidden">
              {songs.map((song, i) => (
                <motion.div
                  key={song.id}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.25, delay: Math.min(i * 0.02, 0.3) }}
                >
                <SongRow
                  song={song}
                  index={i}
                  queue={songs}
                  showAlbumArt
                  showAlbum
                  active={false}
                  selectable={selectMode}
                  selected={selection.selected.has(song.id)}
                  onToggleSelect={() => selection.toggle(song.id)}
                />
                </motion.div>
              ))}
            </div>
          </Section>
        )}
      </div>
    </div>
  )
}

function Section({ label, action, children }: { label: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section>
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-[11px] font-[600] text-on-surface-var uppercase tracking-[1px]">{label}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

function ResultRow({ coverArt, title, subtitle, onClick, coverRound = false }: {
  coverArt?: string; title: string; subtitle?: string; onClick: () => void; coverRound?: boolean
}) {
  const ripple = useRipple()
  return (
    <button
      ref={ripple.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={onClick}
      className="ripple-root group flex items-center gap-3 w-full px-3 py-2.5 rounded-xl
        hover:bg-on-surface/8 transition-colors duration-150"
    >
      <div className={`w-11 h-11 overflow-hidden flex-shrink-0 shadow-elevation-1 ${coverRound ? 'rounded-full' : 'rounded-lg'}`}>
        <CoverArt coverArt={coverArt} size={88} className="w-full h-full object-cover" alt={title} />
      </div>
      <div className="text-left min-w-0">
        <p className="text-[13px] font-[500] text-on-surface truncate">{title}</p>
        {subtitle && <p className="text-[12px] text-on-surface-var truncate">{subtitle}</p>}
      </div>
      <Icon name="chevron_right" size={18} className="text-outline ml-auto opacity-0 group-hover:opacity-100 touch-reveal transition-opacity duration-150" />
    </button>
  )
}
