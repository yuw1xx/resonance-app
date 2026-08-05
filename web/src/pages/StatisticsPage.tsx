import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { subsonic, type SubsonicAlbum, type SubsonicSong } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { Icon } from '@/components/Icon'
import { LineChart } from '@/components/charts/LineChart'
import { BarChart } from '@/components/charts/BarChart'
import { usePlayerStore } from '@/stores/player'
import type { QueueSong } from '@/stores/player'
import {
  getDailyTotals, getDayOfWeekDistribution, getHourlyDistribution,
  getTopArtists, getTopSongs, getStreaks, getTotalListenMs,
} from '@/lib/historyLog'

/* ─── Helpers ────────────────────────────────────────────── */

async function playAlbumById(id: string, play: (songs: QueueSong[], index?: number) => void) {
  try {
    const full = await subsonic.getAlbum(id)
    if (!full.song?.length) return
    play(
      full.song.map(s => ({ ...s, title: s.title ?? 'Unknown', artist: s.artist ?? '', album: s.album ?? '' })),
      0,
    )
  } catch { /* ignore */ }
}

/* ─── Album tile ─────────────────────────────────────────── */

function AlbumTile({ album, rank }: { album: SubsonicAlbum; rank?: number }) {
  const navigate = useNavigate()
  const play = usePlayerStore(s => s.play)

  return (
    <button
      onClick={() => navigate(`/albums/${album.id}`)}
      className="group flex flex-col gap-2 p-2 rounded-2xl text-left
        hover:bg-surface-c transition-all duration-200 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
    >
      <div className="relative w-full aspect-square overflow-hidden rounded-xl bg-surface-high
        shadow-elevation-1 group-hover:shadow-elevation-3 transition-shadow duration-300">
        {rank != null && (
          <div className="absolute top-2 left-2 z-10 w-6 h-6 rounded-full bg-md-bg/80
            flex items-center justify-center text-[11px] font-[700] text-primary">
            {rank}
          </div>
        )}
        <CoverArt
          coverArt={album.coverArt}
          size={200}
          className="w-full h-full object-cover transition-transform duration-350 ease-md-emphasized
            group-hover:scale-[1.04]"
          alt={album.name}
        />
        <button
          onClick={e => { e.stopPropagation(); playAlbumById(album.id, play) }}
          className="absolute bottom-2 right-2 w-9 h-9 rounded-full bg-primary flex items-center justify-center
            shadow-elevation-3 opacity-0 translate-y-1 touch-reveal
            group-hover:opacity-100 group-hover:translate-y-0
            transition-all duration-200 ease-md-emphasized"
          aria-label="Play album"
        >
          <Icon name="play_arrow" size={20} className="text-on-primary ml-[2px]" />
        </button>
      </div>
      <div className="px-1 pb-1 min-w-0">
        <p className="text-[13px] font-[500] text-on-surface truncate leading-snug">{album.name}</p>
        <p className="text-[11px] text-outline truncate leading-snug mt-0.5">
          {album.artist ?? 'Unknown'}
          {album.year ? ` · ${album.year}` : ''}
          {album.playCount ? ` · ${album.playCount} plays` : ''}
        </p>
      </div>
    </button>
  )
}

/* ─── Song row (for random discovery) ───────────────────── */

function SongTile({ song }: { song: SubsonicSong }) {
  const play = usePlayerStore(s => s.play)
  const handlePlay = () =>
    play([{ ...song, title: song.title ?? 'Unknown', artist: song.artist ?? '', album: song.album ?? '' }], 0)

  return (
    <button
      onClick={handlePlay}
      className="group flex items-center gap-3 px-3 py-2.5 rounded-xl text-left
        hover:bg-surface-c transition-colors duration-150"
    >
      <div className="relative w-10 h-10 rounded-lg overflow-hidden flex-shrink-0 shadow-elevation-1">
        <CoverArt coverArt={song.coverArt} size={80} className="w-full h-full object-cover" alt={song.title} />
        <div className="absolute inset-0 flex items-center justify-center bg-md-bg/60
          opacity-0 group-hover:opacity-100 touch-reveal transition-opacity duration-150">
          <Icon name="play_arrow" size={18} className="text-on-surface ml-[2px]" />
        </div>
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[13px] font-[500] text-on-surface truncate">{song.title}</p>
        <p className="text-[11px] text-outline truncate">{song.artist ?? 'Unknown'}</p>
      </div>
    </button>
  )
}

/* ─── Grid skeleton ──────────────────────────────────────── */

function Skeleton({ count = 6 }: { count?: number }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="flex flex-col gap-2 p-2">
          <div className="aspect-square skeleton rounded-xl" />
          <div className="px-1 space-y-1.5 pb-1">
            <div className="h-3 skeleton rounded-full w-4/5" />
            <div className="h-2.5 skeleton rounded-full w-3/5" />
          </div>
        </div>
      ))}
    </>
  )
}

/* ─── Section wrapper ────────────────────────────────────── */

function Section({ title, icon, children, extra }: {
  title: string; icon: string; children: React.ReactNode; extra?: React.ReactNode
}) {
  return (
    <section>
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <Icon name={icon} size={18} className="text-primary" />
          <h2 className="text-[18px] font-[600] text-on-surface tracking-[-0.2px]">{title}</h2>
        </div>
        {extra}
      </div>
      {children}
    </section>
  )
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="col-span-full flex flex-col items-center justify-center py-12 gap-3 text-on-surface-var">
      <Icon name="music_off" size={40} filled={false} className="opacity-20" />
      <p className="text-[13px]">{message}</p>
    </div>
  )
}

/* ─── Local-history sections (client-side log, this device only) ───────── */

function fmtListenTime(ms: number) {
  const totalMinutes = Math.round(ms / 60_000)
  const h = Math.floor(totalMinutes / 60)
  const m = totalMinutes % 60
  if (h === 0) return `${m}m`
  return `${h}h ${m}m`
}

function StatTile({ icon, label, value }: { icon: string; label: string; value: string }) {
  return (
    <div className="flex-1 min-w-[120px] bg-surface-c rounded-2xl p-4 flex flex-col gap-1">
      <Icon name={icon} size={16} className="text-primary" />
      <span className="text-[20px] font-[700] text-on-surface tabular-nums">{value}</span>
      <span className="text-[11px] text-on-surface-var">{label}</span>
    </div>
  )
}

function LocalRankedList({ items }: { items: { label: string; sublabel?: string; count: number }[] }) {
  if (!items.length) {
    return <div className="px-4 py-6 text-center text-[13px] text-on-surface-var">Nothing logged yet</div>
  }
  return (
    <div className="bg-surface-c rounded-2xl overflow-hidden divide-y divide-outline-var/20">
      {items.map((item, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-2.5">
          <span className="w-5 text-[12px] font-[600] text-outline tabular-nums">{i + 1}</span>
          <div className="flex-1 min-w-0">
            <p className="text-[13px] font-[500] text-on-surface truncate">{item.label}</p>
            {item.sublabel && <p className="text-[11px] text-on-surface-var truncate">{item.sublabel}</p>}
          </div>
          <span className="text-[12px] text-outline tabular-nums flex-shrink-0">{item.count} plays</span>
        </div>
      ))}
    </div>
  )
}

/* ─── Page ───────────────────────────────────────────────── */

const GRID = 'grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-2'

export function StatisticsPage() {
  const local = useMemo(() => {
    const daily = getDailyTotals(30)
    const streaks = getStreaks()
    return {
      daily,
      dayOfWeek: getDayOfWeekDistribution(),
      hourly: getHourlyDistribution(),
      topSongs: getTopSongs(8),
      topArtists: getTopArtists(8),
      streaks,
      totalListenMs: getTotalListenMs(),
      hasAny: daily.some(d => d.totalMs > 0),
    }
  }, [])

  const { data: frequent = [], isLoading: lFrequent, isError: eFrequent } = useQuery({
    queryKey: ['stats-frequent'],
    queryFn: () => subsonic.getAlbumList('frequent', 12),
    staleTime: 2 * 60 * 1000,
  })

  const { data: recent = [], isLoading: lRecent } = useQuery({
    queryKey: ['stats-recent'],
    queryFn: () => subsonic.getAlbumList('recent', 12),
    staleTime: 2 * 60 * 1000,
  })

  const { data: newest = [], isLoading: lNewest } = useQuery({
    queryKey: ['stats-newest'],
    queryFn: () => subsonic.getAlbumList('newest', 12),
    staleTime: 5 * 60 * 1000,
  })

  const { data: randomSongs = [], isLoading: lRandom, refetch: refetchRandom } = useQuery({
    queryKey: ['stats-random'],
    queryFn: () => subsonic.getRandomSongs(20),
    staleTime: 0,
  })

  return (
    <div className="flex-1 overflow-y-auto p-6 pb-16 page-enter space-y-10">
      <div>
        <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.5px]">Statistics</h1>
        <p className="text-[13px] text-on-surface-var mt-1">Your listening history from Navidrome</p>
      </div>

      {/* Local listening trends — logged client-side since this device started using Resonance */}
      <section>
        <div className="flex items-center gap-2 mb-1">
          <Icon name="trending_up" size={18} className="text-primary" />
          <h2 className="text-[18px] font-[600] text-on-surface tracking-[-0.2px]">Your Trends</h2>
        </div>
        <p className="text-[12px] text-on-surface-var mb-4">
          Based on plays logged in this browser since you started using Resonance here — not full server history.
        </p>

        {!local.hasAny ? (
          <div className="bg-surface-c rounded-2xl py-10 flex flex-col items-center gap-2 text-on-surface-var">
            <Icon name="insights" size={32} filled={false} className="opacity-30" />
            <p className="text-[13px]">Keep listening — trends appear once plays start logging here.</p>
          </div>
        ) : (
          <div className="space-y-6">
            <div className="flex flex-wrap gap-3">
              <StatTile icon="schedule" label="Listened (30d)" value={fmtListenTime(local.totalListenMs)} />
              <StatTile icon="local_fire_department" label="Current streak" value={`${local.streaks.current}d`} />
              <StatTile icon="emoji_events" label="Longest streak" value={`${local.streaks.longest}d`} />
            </div>

            <div className="bg-surface-c rounded-2xl p-4">
              <h3 className="text-[12px] font-[600] text-on-surface-var uppercase tracking-[0.5px] mb-3">
                Daily listening — last 30 days
              </h3>
              <LineChart
                points={local.daily.map(d => ({ label: d.date.slice(5), value: Math.round(d.totalMs / 60_000) }))}
                height={110}
              />
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="bg-surface-c rounded-2xl p-4">
                <h3 className="text-[12px] font-[600] text-on-surface-var uppercase tracking-[0.5px] mb-3">
                  By day of week
                </h3>
                <BarChart entries={local.dayOfWeek.map(d => ({ label: d.day, value: d.count }))} height={110} />
              </div>
              <div className="bg-surface-c rounded-2xl p-4">
                <h3 className="text-[12px] font-[600] text-on-surface-var uppercase tracking-[0.5px] mb-3">
                  By hour
                </h3>
                <BarChart
                  entries={local.hourly.map(h => ({ label: h.hour % 3 === 0 ? String(h.hour) : '', value: h.count }))}
                  height={110}
                />
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <h3 className="text-[12px] font-[600] text-on-surface-var uppercase tracking-[0.5px] mb-3">
                  Top songs
                </h3>
                <LocalRankedList
                  items={local.topSongs.map(s => ({ label: s.title, sublabel: s.artist, count: s.count }))}
                />
              </div>
              <div>
                <h3 className="text-[12px] font-[600] text-on-surface-var uppercase tracking-[0.5px] mb-3">
                  Top artists
                </h3>
                <LocalRankedList
                  items={local.topArtists.map(a => ({ label: a.artist, count: a.count }))}
                />
              </div>
            </div>
          </div>
        )}
      </section>

      {/* Most played */}
      <Section title="Most Played" icon="bar_chart">
        <div className={GRID}>
          {lFrequent ? (
            <Skeleton />
          ) : eFrequent || frequent.length === 0 ? (
            <EmptyState message="No play history yet — start listening and it'll appear here." />
          ) : (
            frequent.map((album, i) => <AlbumTile key={album.id} album={album} rank={i + 1} />)
          )}
        </div>
      </Section>

      {/* Recently played */}
      <Section title="Recently Played" icon="history">
        <div className={GRID}>
          {lRecent ? (
            <Skeleton />
          ) : recent.length === 0 ? (
            <EmptyState message="No recent plays — scrobbling must be on for history to appear." />
          ) : (
            recent.map(album => <AlbumTile key={album.id} album={album} />)
          )}
        </div>
      </Section>

      {/* Newest additions */}
      <Section title="Newest Additions" icon="new_releases">
        <div className={GRID}>
          {lNewest ? <Skeleton /> : newest.map(album => <AlbumTile key={album.id} album={album} />)}
        </div>
      </Section>

      {/* Random discovery */}
      <Section
        title="Random Discovery"
        icon="shuffle"
        extra={
          <button
            onClick={() => refetchRandom()}
            className="flex items-center gap-1.5 text-[12px] text-primary hover:text-primary/80 font-[500]
              transition-colors duration-150"
          >
            <Icon name="refresh" size={14} />
            Shuffle
          </button>
        }
      >
        <div className="bg-surface-c rounded-2xl overflow-hidden divide-y divide-outline-var/20">
          {lRandom ? (
            <div className="p-4 flex items-center justify-center">
              <div className="w-5 h-5 border-2 border-primary border-t-transparent rounded-full animate-spin" />
            </div>
          ) : randomSongs.length === 0 ? (
            <div className="px-4 py-6 text-center text-[13px] text-on-surface-var">No songs available</div>
          ) : (
            <div className="p-2 grid grid-cols-1 sm:grid-cols-2 gap-0.5">
              {randomSongs.map(song => <SongTile key={song.id} song={song} />)}
            </div>
          )}
        </div>
      </Section>
    </div>
  )
}
