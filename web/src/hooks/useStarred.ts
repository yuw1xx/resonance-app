import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { subsonic } from '@/api/subsonic'
import { toast } from '@/components/Toast'

const STARRED_IDS_KEY = ['starredIds']
const STARRED_SONGS_KEY = ['starredSongs']

export function useStarredIds() {
  const { data } = useQuery({
    queryKey: STARRED_IDS_KEY,
    queryFn: () => subsonic.getStarred2().then(r => new Set((r.song ?? []).map(s => s.id))),
    staleTime: 60_000,
  })
  return data ?? new Set<string>()
}

export function useStarredSongs() {
  return useQuery({
    queryKey: STARRED_SONGS_KEY,
    queryFn: () => subsonic.getStarred2().then(r => r.song ?? []),
    staleTime: 30_000,
  })
}

export function useToggleStar() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, starred }: { id: string; starred: boolean }) =>
      starred ? subsonic.unstar(id) : subsonic.star(id),

    onMutate: async ({ id, starred }) => {
      await queryClient.cancelQueries({ queryKey: STARRED_IDS_KEY })
      const previous = queryClient.getQueryData<Set<string>>(STARRED_IDS_KEY)
      queryClient.setQueryData<Set<string>>(STARRED_IDS_KEY, old => {
        const next = new Set(old ?? [])
        if (starred) next.delete(id)
        else next.add(id)
        return next
      })
      return { previous }
    },

    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(STARRED_IDS_KEY, context.previous)
      toast('Couldn\'t update — try again')
    },

    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: STARRED_IDS_KEY })
      queryClient.invalidateQueries({ queryKey: STARRED_SONGS_KEY })
    },
  })
}
