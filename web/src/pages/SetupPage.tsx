import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import { Icon } from '@/components/Icon'

export function SetupPage() {
  const login = useAuthStore(s => s.login)
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const navigate = useNavigate()
  const [serverUrl, setServerUrl] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  // Landing here directly while already logged in (bookmark, back button, manual URL) — same
  // gap as the one below, just approached from the other direction.
  useEffect(() => {
    if (isAuthenticated) navigate('/', { replace: true })
  }, [isAuthenticated, navigate])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await login(serverUrl, username, password)
      navigate('/', { replace: true })
    } catch (err) {
      // fetch() itself rejects with a TypeError for any network-level failure — a wrong
      // hostname, a server that's down, or (by far the most common cause once the URL is
      // otherwise correct) the server rejecting the cross-origin request outright before this
      // page's JS ever sees a response. The browser deliberately doesn't say which one it was,
      // so this can't be certain — but it's specific and actionable enough to lead with.
      // A Subsonic-level error (wrong password, etc.) means the request reached the server
      // fine and comes through as a plain Error with the server's own message instead.
      if (err instanceof TypeError) {
        setError(
          'Couldn\'t reach that server from your browser. Double-check the URL, that the ' +
          'server is running and reachable from this device, and that its navidrome.toml has ' +
          'CORSAllowOrigins set to allow this site (see below) — that\'s the most common cause.',
        )
      } else {
        setError(err instanceof Error ? err.message : 'Could not connect to server.')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-md-bg flex items-center justify-center p-4">
      {/* Ambient background */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden">
        <div className="absolute -top-32 left-1/2 -translate-x-1/2 w-[600px] h-[400px] rounded-full opacity-10"
          style={{ background: 'radial-gradient(ellipse, #D0BCFF, transparent)' }} />
      </div>

      <div className="relative w-full max-w-[400px] animate-scale-in">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="w-16 h-16 rounded-2xl bg-primary-container flex items-center justify-center mx-auto mb-4 shadow-elevation-2">
            <Icon name="music_note" size={32} className="text-on-primary-container" />
          </div>
          <h1 className="text-[28px] font-[700] text-on-surface tracking-[-0.5px]">Resonance</h1>
          <p className="text-[14px] text-on-surface-var mt-1.5">Connect to your Navidrome server</p>
        </div>

        {/* Card */}
        <div className="bg-surface-c rounded-2xl p-6 shadow-elevation-2 border border-outline-var/30">
          <form onSubmit={handleSubmit} className="space-y-4">
            <Field
              label="Server URL"
              type="url"
              value={serverUrl}
              onChange={setServerUrl}
              placeholder="http://your-server:4533"
              icon="dns"
            />
            <Field
              label="Username"
              type="text"
              value={username}
              onChange={setUsername}
              placeholder="admin"
              icon="person"
            />
            <Field
              label="Password"
              type="password"
              value={password}
              onChange={setPassword}
              placeholder="••••••••"
              icon="lock"
            />

            {error && (
              <div className="flex items-start gap-2 bg-error-container/20 border border-error-container/50 rounded-lg px-3 py-2.5">
                <Icon name="error" size={16} className="text-error flex-shrink-0 mt-0.5" />
                <p className="text-[12px] text-on-error-container">{error}</p>
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full bg-primary hover:brightness-95 disabled:opacity-50
                text-on-primary font-[600] text-[14px] py-3 rounded-xl
                transition-all duration-200 ease-md-standard
                shadow-elevation-1 hover:shadow-elevation-2 active:scale-[0.98]"
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <span className="w-4 h-4 border-2 border-on-primary/30 border-t-on-primary rounded-full animate-spin" />
                  Connecting…
                </span>
              ) : (
                'Connect'
              )}
            </button>
          </form>
        </div>

        <p className="text-center text-[12px] text-outline mt-4">
          Requires{' '}
          <code className="bg-surface-c px-1 py-0.5 rounded text-on-surface-var">CORSAllowOrigins = "*"</code>
          {' '}in navidrome.toml
        </p>
      </div>
    </div>
  )
}

function Field({
  label, type, value, onChange, placeholder, icon,
}: {
  label: string
  type: string
  value: string
  onChange: (v: string) => void
  placeholder: string
  icon: string
}) {
  return (
    <div>
      <label className="block text-[11px] font-[600] text-on-surface-var uppercase tracking-[0.8px] mb-1.5">
        {label}
      </label>
      <div className="relative">
        <Icon name={icon} size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-outline pointer-events-none" />
        <input
          type={type}
          value={value}
          onChange={e => onChange(e.target.value)}
          placeholder={placeholder}
          required
          className="w-full bg-surface-high border border-outline-var/50 rounded-xl
            pl-9 pr-4 py-2.5 text-[13px] text-on-surface placeholder-outline
            focus:border-primary focus:ring-2 focus:ring-primary/20
            transition-all duration-200 ease-md-standard"
        />
      </div>
    </div>
  )
}
