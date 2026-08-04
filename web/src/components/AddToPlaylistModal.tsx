import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { Modal, ModalButton, ModalTextField } from './Modal'
import { Icon } from './Icon'
import { useRipple } from './Ripple'
import { toast } from './Toast'

interface Props {
  open: boolean
  onClose: () => void
  songIds: string[]
}

function PlaylistRow({ id, name, songCount, onAdd }: {
  id: string; name: string; songCount?: number; onAdd: () => void
}) {
  const ripple = useRipple()
  return (
    <button
      ref={ripple.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={ripple.onPointerDown}
      onClick={onAdd}
      className="ripple-root flex items-center gap-3 w-full px-3 py-2.5 rounded-xl text-left
        hover:bg-on-surface/8 transition-colors duration-150"
    >
      <div className="w-9 h-9 rounded-lg bg-surface-high flex items-center justify-center flex-shrink-0">
        <Icon name="queue_music" size={16} className="text-on-surface-var" filled={false} />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[13px] font-[500] text-on-surface truncate">{name}</p>
        <p className="text-[11px] text-on-surface-var">{songCount ?? 0} songs</p>
      </div>
      <Icon name="add" size={18} className="text-on-surface-var" />
    </button>
  )
}

export function AddToPlaylistModal({ open, onClose, songIds }: Props) {
  const queryClient = useQueryClient()
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')

  const { data: playlists = [] } = useQuery({
    queryKey: ['playlists'],
    queryFn: () => subsonic.getPlaylists(),
    staleTime: 5 * 60 * 1000,
    enabled: open,
  })

  const addMutation = useMutation({
    mutationFn: (playlistId: string) =>
      subsonic.updatePlaylist(playlistId, { songIdsToAdd: songIds }),
    onSuccess: (_, playlistId) => {
      queryClient.invalidateQueries({ queryKey: ['playlist', playlistId] })
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      toast(songIds.length > 1 ? `Added ${songIds.length} songs` : 'Added to playlist')
      onClose()
    },
    onError: () => toast('Couldn\'t add — try again'),
  })

  const createMutation = useMutation({
    mutationFn: (name: string) => subsonic.createPlaylist(name, songIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      toast('Playlist created')
      setCreating(false)
      setNewName('')
      onClose()
    },
    onError: () => toast('Couldn\'t create playlist — try again'),
  })

  return (
    <Modal open={open} onClose={onClose} title="Add to playlist">
      {creating ? (
        <div className="space-y-3 py-1">
          <ModalTextField value={newName} onChange={setNewName} placeholder="Playlist name" autoFocus />
          <div className="flex justify-end gap-2">
            <ModalButton label="Cancel" tonal onClick={() => setCreating(false)} />
            <ModalButton
              label="Create"
              disabled={!newName.trim() || createMutation.isPending}
              onClick={() => createMutation.mutate(newName.trim())}
            />
          </div>
        </div>
      ) : (
        <div className="space-y-1 max-h-[50vh] overflow-y-auto -mx-1">
          <button
            onClick={() => setCreating(true)}
            className="flex items-center gap-3 w-full px-3 py-2.5 rounded-xl text-left
              hover:bg-on-surface/8 transition-colors duration-150"
          >
            <div className="w-9 h-9 rounded-lg bg-primary/12 flex items-center justify-center flex-shrink-0">
              <Icon name="add" size={16} className="text-primary" />
            </div>
            <p className="text-[13px] font-[600] text-primary">New playlist</p>
          </button>
          {playlists.length === 0 && (
            <p className="text-[13px] text-on-surface-var px-3 py-4">No playlists yet</p>
          )}
          {playlists.map(pl => (
            <PlaylistRow
              key={pl.id}
              id={pl.id}
              name={pl.name}
              songCount={pl.songCount}
              onAdd={() => addMutation.mutate(pl.id)}
            />
          ))}
        </div>
      )}
    </Modal>
  )
}
