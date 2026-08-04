import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { CoverArt } from '@/components/CoverArt'
import { Icon } from '@/components/Icon'
import { useRipple } from '@/components/Ripple'
import { Modal, ModalButton, ModalTextField } from '@/components/Modal'
import { toast } from '@/components/Toast'

function fmtDur(s?: number) {
  if (!s) return ''
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m} min`
}

function Skeleton() {
  return (
    <div className="flex items-center gap-4 px-3 py-3">
      <div className="w-14 h-14 skeleton rounded-xl flex-shrink-0" />
      <div className="flex-1 space-y-2">
        <div className="h-3.5 skeleton rounded-full w-2/5" />
        <div className="h-2.5 skeleton rounded-full w-1/4" />
      </div>
    </div>
  )
}

export function PlaylistsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [newName, setNewName] = useState('')

  const { data: playlists = [], isLoading } = useQuery({
    queryKey: ['playlists'],
    queryFn: () => subsonic.getPlaylists(),
    staleTime: 5 * 60 * 1000,
  })

  const createMutation = useMutation({
    mutationFn: (name: string) => subsonic.createPlaylist(name),
    onSuccess: playlist => {
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      setShowCreate(false)
      setNewName('')
      navigate(`/playlists/${playlist.id}`)
    },
    onError: () => toast('Couldn\'t create playlist — try again'),
  })

  return (
    <div className="flex-1 overflow-y-auto p-6 page-enter">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-[22px] font-[600] text-on-surface tracking-[-0.3px]">Playlists</h1>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-full text-[13px] font-[600]
            bg-primary text-on-primary shadow-elevation-2 hover:scale-[1.02] active:scale-[0.97]
            transition-all duration-200 ease-md-standard"
        >
          <Icon name="add" size={16} />
          New Playlist
        </button>
      </div>

      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title="New playlist"
        footer={
          <>
            <ModalButton label="Cancel" tonal onClick={() => setShowCreate(false)} />
            <ModalButton
              label="Create"
              disabled={!newName.trim() || createMutation.isPending}
              onClick={() => createMutation.mutate(newName.trim())}
            />
          </>
        }
      >
        <ModalTextField value={newName} onChange={setNewName} placeholder="Playlist name" autoFocus />
      </Modal>

      <div className="bg-surface-c rounded-2xl overflow-hidden">
        {isLoading
          ? Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} />)
          : playlists.map((pl, i) => (
              <PlaylistRow key={pl.id} pl={pl} onClick={() => navigate(`/playlists/${pl.id}`)} last={i === playlists.length - 1} />
            ))
        }
        {!isLoading && playlists.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 gap-3 text-on-surface-var">
            <Icon name="queue_music" size={40} filled={false} className="opacity-30" />
            <p className="text-[13px]">No playlists yet</p>
          </div>
        )}
      </div>
    </div>
  )
}

function PlaylistRow({ pl, onClick, last }: {
  pl: { id: string; name: string; songCount?: number; duration?: number; coverArt?: string }
  onClick: () => void
  last: boolean
}) {
  const ripple = useRipple()
  return (
    <button
      ref={ripple.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={onClick}
      className={`ripple-root group flex items-center gap-4 w-full px-4 py-3.5
        hover:bg-on-surface/8 transition-colors duration-150 text-left
        ${!last ? 'border-b border-outline-var/20' : ''}`}
    >
      <div className="w-14 h-14 rounded-xl overflow-hidden flex-shrink-0 shadow-elevation-1">
        <CoverArt coverArt={pl.coverArt} size={112} className="w-full h-full object-cover" alt={pl.name} />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[14px] font-[500] text-on-surface truncate">{pl.name}</p>
        <p className="text-[12px] text-on-surface-var mt-0.5">
          {pl.songCount ?? 0} songs{pl.duration ? ` · ${fmtDur(pl.duration)}` : ''}
        </p>
      </div>
      <Icon name="chevron_right" size={20} className="text-outline opacity-0 group-hover:opacity-100 transition-opacity duration-150" />
    </button>
  )
}
