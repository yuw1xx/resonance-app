import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { getSession } from '@/lib/lastfm'
import { useLastFmStore } from '@/stores/lastfm'
import { toast } from '@/components/Toast'
import { Icon } from '@/components/Icon'

export function LastFmCallbackPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const setSession = useLastFmStore(s => s.setSession)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const token = searchParams.get('token')
    if (!token) { setFailed(true); return }
    getSession(token)
      .then(({ key, name }) => {
        setSession(key, name)
        toast(`Connected to Last.fm as ${name}`)
        navigate('/settings', { replace: true })
      })
      .catch(() => setFailed(true))
    // Runs once on mount — searchParams/navigate/setSession are stable enough here and
    // re-running this on their identity churn would risk a double auth.getSession call.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="min-h-screen bg-md-bg flex items-center justify-center p-4">
      <div className="text-center max-w-[320px]">
        {failed ? (
          <>
            <Icon name="error" size={32} className="text-error mx-auto mb-3" />
            <p className="text-on-surface text-[15px] font-[600] mb-1">Couldn't connect to Last.fm</p>
            <p className="text-on-surface-var text-[13px] mb-4">The authorization didn't complete. You can try again from Settings.</p>
            <button
              onClick={() => navigate('/settings', { replace: true })}
              className="text-primary text-[13px] font-[600] hover:text-primary/80 transition-colors duration-150"
            >
              Back to Settings
            </button>
          </>
        ) : (
          <>
            <div className="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin mx-auto mb-4" />
            <p className="text-on-surface-var text-[14px]">Connecting to Last.fm…</p>
          </>
        )}
      </div>
    </div>
  )
}
