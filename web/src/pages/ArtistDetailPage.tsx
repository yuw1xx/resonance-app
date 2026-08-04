import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { AlbumCard } from '@/components/AlbumCard'
import { Icon } from '@/components/Icon'
import { share } from '@/lib/share'
import { lookupArtist as lookupDiscogsArtist } from '@/api/discogs'
import { useSettingsStore } from '@/stores/settings'

// Navidrome's Last.fm-sourced bios often carry a trailing "<a ...>Read more...</a>" —
// render as plain text rather than dangerouslySetInnerHTML, and surface lastFmUrl separately.
function stripHtml(s: string) {
  return s.replace(/<[^>]*>/g, '').trim()
}

// Discogs profile fields use their own bracket markup, not HTML — strip it the same way,
// keeping the inner text of link-like tags rather than dropping it.
function stripDiscogsMarkup(s: string) {
  return s
    .replace(/\[a=\d+\]([^[]*)\[\/a\]/g, '$1')
    .replace(/\[url=[^\]]*\]([^[]*)\[\/url\]/g, '$1')
    .replace(/\[\/?[a-z]+\]/gi, '')
    .trim()
}

export function ArtistDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [bioExpanded, setBioExpanded] = useState(false)

  const { data: artist, isLoading } = useQuery({
    queryKey: ['artist', id],
    queryFn: () => subsonic.getArtist(id!),
    staleTime: 5 * 60 * 1000,
  })

  const { data: info } = useQuery({
    queryKey: ['artistInfo', id],
    queryFn: () => subsonic.getArtistInfo2(id!),
    staleTime: 30 * 60 * 1000,
    enabled: !!id,
  })

  const discogsToken = useSettingsStore(s => s.discogsToken)
  const { data: discogs } = useQuery({
    queryKey: ['discogsArtist', artist?.name],
    queryFn: () => lookupDiscogsArtist(artist!.name, discogsToken),
    staleTime: 30 * 60 * 1000,
    enabled: !!artist?.name && !!discogsToken,
    retry: false,
  })

  const bio = info?.biography ? stripHtml(info.biography) : ''
  const discogsProfile = discogs?.profile ? stripDiscogsMarkup(discogs.profile) : ''
  const activeMembers = discogs?.members?.filter(m => m.active !== false) ?? []

  if (isLoading) {
    return (
      <div className="flex-1 overflow-y-auto page-enter p-6">
        <div className="flex gap-6 items-end">
          <div className="w-32 h-32 skeleton rounded-full flex-shrink-0" />
          <div className="flex-1 space-y-2 pb-2">
            <div className="h-4 skeleton rounded-full w-1/4" />
            <div className="h-7 skeleton rounded-full w-2/5" />
            <div className="h-3.5 skeleton rounded-full w-1/4 mt-3" />
          </div>
        </div>
      </div>
    )
  }

  if (!artist) return (
    <div className="flex-1 flex items-center justify-center text-on-surface-var">Artist not found</div>
  )

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      <div className="relative px-6 pt-6 pb-8" style={{
        background: 'linear-gradient(180deg, rgba(208,188,255,0.06) 0%, transparent 100%)'
      }}>
        <div className="flex items-center justify-between mb-5">
          <button
            onClick={() => navigate(-1)}
            className="flex items-center gap-1 text-[13px] text-on-surface-var hover:text-on-surface transition-colors duration-150"
          >
            <Icon name="arrow_back" size={16} />
            Back
          </button>
          <button
            onClick={() => share(window.location.href, artist.name)}
            aria-label="Share artist"
            className="w-9 h-9 rounded-full flex items-center justify-center
              text-on-surface-var hover:bg-on-surface/8 hover:text-on-surface transition-colors duration-150"
          >
            <Icon name="share" size={18} filled={false} />
          </button>
        </div>

        <div className="flex gap-6 items-end">
          <div className="w-32 h-32 rounded-full overflow-hidden shadow-elevation-3 flex-shrink-0">
            <CoverArt coverArt={artist.coverArt} size={256} className="w-full h-full object-cover" alt={artist.name} />
          </div>
          <div className="pb-1">
            <p className="text-[11px] font-[600] text-primary uppercase tracking-[1px] mb-2">Artist</p>
            <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.4px] leading-tight">
              {artist.name}
            </h1>
            {artist.albumCount != null && (
              <p className="text-[13px] text-on-surface-var mt-1">
                {artist.albumCount} {artist.albumCount === 1 ? 'album' : 'albums'}
              </p>
            )}
          </div>
        </div>
      </div>

      <div className="mx-6 h-px bg-outline-var/30" />

      {(bio || discogsProfile || activeMembers.length > 0) && (
        <div className="px-6 pt-6">
          <h2 className="text-[15px] font-[600] text-on-surface mb-3">About</h2>
          <div className="bg-surface-c rounded-2xl p-5 space-y-4">
            {bio && (
              <div>
                <p className={`text-[13px] leading-relaxed text-on-surface-var ${bioExpanded ? '' : 'line-clamp-4'}`}>
                  {bio}
                </p>
                <div className="flex items-center gap-4 mt-3">
                  {bio.length > 240 && (
                    <button
                      onClick={() => setBioExpanded(v => !v)}
                      className="text-[12px] font-[600] text-primary hover:text-primary/80 transition-colors duration-150"
                    >
                      {bioExpanded ? 'Show less' : 'Show more'}
                    </button>
                  )}
                  {info?.lastFmUrl && (
                    <a
                      href={info.lastFmUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-[12px] font-[500] text-on-surface-var hover:text-on-surface transition-colors duration-150"
                    >
                      View on Last.fm
                    </a>
                  )}
                </div>
              </div>
            )}

            {!!info?.similarArtist?.length && (
              <div className="flex flex-wrap gap-2">
                {info.similarArtist.map((a, i) => (
                  <button
                    key={a.id ?? `${a.name}-${i}`}
                    onClick={() => a.id && navigate(`/artists/${a.id}`)}
                    disabled={!a.id}
                    className="px-3 py-1.5 rounded-full text-[12px] font-[500] bg-surface-high text-on-surface-var
                      hover:bg-surface-highest hover:text-on-surface transition-colors duration-150
                      disabled:opacity-50 disabled:pointer-events-none"
                  >
                    {a.name}
                  </button>
                ))}
              </div>
            )}

            {(discogsProfile || activeMembers.length > 0) && (
              <div className={bio ? 'pt-4 border-t border-outline-var/15' : ''}>
                <div className="flex items-center gap-1.5 mb-2">
                  <Icon name="album" size={13} className="text-outline" />
                  <span className="text-[10px] font-[600] text-outline uppercase tracking-[0.8px]">From Discogs</span>
                </div>
                {discogsProfile && (
                  <p className="text-[13px] leading-relaxed text-on-surface-var mb-3">{discogsProfile}</p>
                )}
                {activeMembers.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {activeMembers.map(m => (
                      <span
                        key={m.id}
                        className="px-3 py-1.5 rounded-full text-[12px] font-[500] bg-surface-high text-on-surface-var"
                      >
                        {m.name}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      <div className="px-6 pb-8 pt-6">
        <h2 className="text-[15px] font-[600] text-on-surface mb-4">Albums</h2>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3">
          {(artist.album ?? []).map((album, i) => <AlbumCard key={album.id} album={album} index={i} />)}
        </div>
      </div>
    </div>
  )
}
