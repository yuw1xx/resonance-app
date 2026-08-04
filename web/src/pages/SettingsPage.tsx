import { useRef, useState } from 'react'
import { useAuthStore } from '@/stores/auth'
import { applyEqPreset, applyPreservePitch } from '@/stores/player'
import {
  useSettingsStore,
  type AudioQuality, type ReplayGain, type GridDensity,
  type ScrobbleThreshold, type DefaultSort,
  type AccentTheme, type PlayerBackground, type LyricsSize, type LyricsAlign,
  type AnimationSpeed, type EqPreset, type QueueEndBehavior, type PlaybackSpeed,
  type PlayerArtworkShape, type RecentlyAddedCount,
} from '@/stores/settings'
import { audio } from '@/stores/player'
import { themeColors } from '@/lib/themes'
import { Icon } from '@/components/Icon'
import { Modal, ModalTextField, ModalButton } from '@/components/Modal'
import { toast } from '@/components/Toast'
import { useLastFmStore } from '@/stores/lastfm'
import { getToken as getLastFmToken, buildAuthUrl as buildLastFmAuthUrl } from '@/lib/lastfm'
import { useMalojaStore } from '@/stores/maloja'
import { testConnection as testMalojaConnection } from '@/lib/maloja'
import { downloadBackup, importBackupFile } from '@/lib/backup'
import { clearHistoryLog } from '@/lib/historyLog'

/* ─── Primitives ─────────────────────────────────────────── */

function Switch({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`relative w-[52px] h-8 rounded-full border-2 flex-shrink-0 transition-all duration-200
        focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2
        focus-visible:ring-offset-md-bg
        ${checked ? 'bg-primary border-primary' : 'bg-transparent border-outline'}`}
    >
      <span
        className={`absolute top-1/2 -translate-y-1/2 rounded-full transition-all duration-200
          ${checked
            ? 'bg-on-primary w-6 h-6 right-[3px]'
            : 'bg-outline w-4 h-4 left-[3px]'
          }`}
      />
    </button>
  )
}

function Chips<T extends string | number>({
  options, value, onChange,
}: {
  options: { label: string; value: T; icon?: string }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {options.map(opt => (
        <button
          key={String(opt.value)}
          type="button"
          onClick={() => onChange(opt.value)}
          className={`flex items-center gap-1.5 px-4 py-1.5 rounded-full text-[13px] font-[500]
            transition-all duration-150 border focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary
            ${value === opt.value
              ? 'bg-secondary-container text-on-secondary-container border-transparent scale-[1.02]'
              : 'bg-transparent text-on-surface-var border-outline-var hover:bg-on-surface/8 hover:text-on-surface'
            }`}
        >
          {opt.icon && <Icon name={opt.icon} size={14} filled={value === opt.value} />}
          {opt.label}
        </button>
      ))}
    </div>
  )
}

/* ─── Theme color picker ──────────────────────────────────── */

const THEME_META: { id: AccentTheme; label: string }[] = [
  { id: 'purple', label: 'Purple' },
  { id: 'blue',   label: 'Blue' },
  { id: 'green',  label: 'Green' },
  { id: 'orange', label: 'Orange' },
  { id: 'pink',   label: 'Pink' },
  { id: 'red',    label: 'Red' },
  { id: 'teal',   label: 'Teal' },
]

function ThemePicker({ value, onChange }: { value: AccentTheme; onChange: (v: AccentTheme) => void }) {
  return (
    <div className="flex flex-wrap gap-3 py-1">
      {THEME_META.map(t => (
        <button
          key={t.id}
          title={t.label}
          onClick={() => onChange(t.id)}
          className={`group flex flex-col items-center gap-2 p-2 rounded-xl transition-all duration-200
            ${value === t.id ? 'bg-surface-high' : 'hover:bg-surface-high/60'}`}
        >
          <div
            className={`w-8 h-8 rounded-full transition-all duration-200 ring-offset-2 ring-offset-surface-c
              ${value === t.id ? 'ring-2 scale-110' : 'group-hover:scale-105'}`}
            style={{ backgroundColor: themeColors[t.id], outline: value === t.id ? `2px solid ${themeColors[t.id]}` : 'none', outlineOffset: 2 }}
          />
          <span className={`text-[10px] font-[500] transition-colors ${value === t.id ? 'text-on-surface' : 'text-outline'}`}>
            {t.label}
          </span>
        </button>
      ))}
    </div>
  )
}

/* ─── EQ Preset picker with mini curve preview ────────────── */

const EQ_PRESETS_META: { id: EqPreset; label: string; desc: string; gains: number[] }[] = [
  { id: 'flat',       label: 'Flat',       desc: 'No adjustments',           gains: [0,0,0,0,0,0,0,0,0,0] },
  { id: 'bass-boost', label: 'Bass Boost', desc: 'Punchy low end',            gains: [7,6,4,2,0,0,0,0,0,0] },
  { id: 'vocal',      label: 'Vocal',      desc: 'Clear mids, smooth highs',  gains: [-2,-2,0,2,4,5,4,2,0,-2] },
  { id: 'treble',     label: 'Treble',     desc: 'Bright & airy',             gains: [0,0,0,0,0,0,2,4,6,7] },
  { id: 'classical',  label: 'Classical',  desc: 'Spacious & warm',           gains: [5,4,3,0,0,0,0,2,3,4] },
  { id: 'pop',        label: 'Pop',        desc: 'Smiley curve',              gains: [-1,2,4,4,2,0,-1,0,1,2] },
]

function EqCurve({ gains }: { gains: number[] }) {
  const w = 80, h = 32, maxG = 8
  const pts = gains.map((g, i) => {
    const x = (i / (gains.length - 1)) * w
    const y = h / 2 - (g / maxG) * (h / 2 - 2)
    return `${x},${y}`
  })
  const d = pts.reduce((acc, pt, i) => {
    if (i === 0) return `M ${pt}`
    const [px, py] = pts[i - 1].split(',').map(Number)
    const [cx, cy] = pt.split(',').map(Number)
    const mx = (px + cx) / 2
    return `${acc} C ${mx},${py} ${mx},${cy} ${cx},${cy}`
  }, '')
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="opacity-70">
      <line x1={0} y1={h/2} x2={w} y2={h/2} stroke="currentColor" strokeOpacity={0.15} />
      <path d={d} fill="none" stroke="var(--md-primary)" strokeWidth={1.5} strokeLinecap="round" />
    </svg>
  )
}

function EqPresetPicker({ value, onChange }: { value: EqPreset; onChange: (v: EqPreset) => void }) {
  return (
    <div className="grid grid-cols-2 gap-2">
      {EQ_PRESETS_META.map(p => (
        <button
          key={p.id}
          onClick={() => onChange(p.id)}
          className={`flex flex-col gap-2 p-3 rounded-xl text-left transition-all duration-200 border
            ${value === p.id
              ? 'bg-primary-container border-primary/40 text-on-primary-container'
              : 'bg-surface-high border-transparent hover:bg-surface-highest text-on-surface-var'
            }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-[13px] font-[600]">{p.label}</span>
            {value === p.id && <Icon name="check_circle" size={14} className="text-primary" />}
          </div>
          <EqCurve gains={p.gains} />
          <span className="text-[11px] opacity-70">{p.desc}</span>
        </button>
      ))}
    </div>
  )
}

/* ─── Section wrapper ────────────────────────────────────── */

function Section({ title, icon, children, accent = false }: { title: string; icon: string; children: React.ReactNode; accent?: boolean }) {
  return (
    <section>
      <div className="flex items-center gap-2 mb-3 px-1">
        <Icon name={icon} size={16} className={accent ? 'text-primary' : 'text-primary'} />
        <h2 className="text-[11px] font-[600] text-primary uppercase tracking-[1.2px]">{title}</h2>
      </div>
      <div className="bg-surface-c rounded-2xl overflow-hidden divide-y divide-outline-var/20">
        {children}
      </div>
    </section>
  )
}

/* ─── Row types ──────────────────────────────────────────── */

function SwitchRow({
  label, description, checked, onChange, icon,
}: { label: string; description?: string; checked: boolean; onChange: (v: boolean) => void; icon?: string }) {
  return (
    <div className="flex items-center justify-between gap-4 px-5 py-4">
      <div className="flex items-center gap-3 min-w-0">
        {icon && <Icon name={icon} size={18} className="text-on-surface-var flex-shrink-0" filled={false} />}
        <div className="min-w-0">
          <p className="text-[14px] font-[500] text-on-surface">{label}</p>
          {description && <p className="text-[12px] text-on-surface-var mt-0.5">{description}</p>}
        </div>
      </div>
      <Switch checked={checked} onChange={onChange} />
    </div>
  )
}

function ChipsRow<T extends string | number>({
  label, description, options, value, onChange,
}: {
  label: string; description?: string
  options: { label: string; value: T; icon?: string }[]
  value: T; onChange: (v: T) => void
}) {
  return (
    <div className="px-5 py-4 space-y-3">
      <div>
        <p className="text-[14px] font-[500] text-on-surface">{label}</p>
        {description && <p className="text-[12px] text-on-surface-var mt-0.5">{description}</p>}
      </div>
      <Chips options={options} value={value} onChange={onChange} />
    </div>
  )
}

function SliderRow({
  label, description, value, min, max, step, format, onChange,
}: {
  label: string; description?: string; value: number; min: number; max: number; step: number
  format?: (v: number) => string; onChange: (v: number) => void
}) {
  const pct = ((value - min) / (max - min)) * 100
  return (
    <div className="px-5 py-4 space-y-3">
      <div className="flex justify-between items-baseline">
        <div>
          <p className="text-[14px] font-[500] text-on-surface">{label}</p>
          {description && <p className="text-[12px] text-on-surface-var mt-0.5">{description}</p>}
        </div>
        <span className="text-[13px] font-[600] text-primary tabular-nums ml-4">
          {format ? format(value) : value}
        </span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={e => onChange(Number(e.target.value))}
        className="settings-slider"
        style={{ '--pct': `${pct}%` } as React.CSSProperties}
      />
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 px-5 py-4">
      <span className="text-[14px] text-on-surface-var">{label}</span>
      <span className="text-[13px] text-on-surface font-[500] text-right truncate max-w-[60%]">{value}</span>
    </div>
  )
}

function CustomRow({ label, description, children }: { label: string; description?: string; children: React.ReactNode }) {
  return (
    <div className="px-5 py-4 space-y-3">
      <div>
        <p className="text-[14px] font-[500] text-on-surface">{label}</p>
        {description && <p className="text-[12px] text-on-surface-var mt-0.5">{description}</p>}
      </div>
      {children}
    </div>
  )
}

/* ─── Online Services ────────────────────────────────────── */

function LastFmCard() {
  const { sessionKey, username, enabled, nowPlayingEnabled, thresholdSeconds, thresholdPercent, signOut, set } = useLastFmStore()
  const [connecting, setConnecting] = useState(false)

  async function connect() {
    setConnecting(true)
    try {
      const token = await getLastFmToken()
      // BASE_URL already ends in '/' — resolves correctly under a subpath deploy too.
      window.location.href = buildLastFmAuthUrl(token, `${window.location.origin}${import.meta.env.BASE_URL}lastfm-callback`)
    } catch {
      toast('Couldn\'t start Last.fm authorization — try again')
      setConnecting(false)
    }
  }

  return (
    <Section title="Last.fm" icon="podcasts">
      {!sessionKey ? (
        <div className="px-5 py-4">
          <p className="text-[13px] text-on-surface-var mb-3">Scrobble what you play to your Last.fm profile.</p>
          <ModalButton label={connecting ? 'Opening Last.fm…' : 'Connect Last.fm'} onClick={connect} disabled={connecting} />
        </div>
      ) : (
        <>
          <InfoRow label="Connected as" value={username ?? ''} />
          <SwitchRow
            label="Enable scrobbling"
            checked={enabled}
            onChange={v => set('enabled', v)}
          />
          {enabled && (
            <>
              <SwitchRow
                label="Show Now Playing"
                description="Update your Last.fm profile as soon as a track starts."
                checked={nowPlayingEnabled}
                onChange={v => set('nowPlayingEnabled', v)}
              />
              <SliderRow
                label="Scrobble after"
                description="Whichever of these two happens first."
                value={thresholdSeconds}
                min={10}
                max={120}
                step={5}
                format={v => `${v}s`}
                onChange={v => set('thresholdSeconds', v)}
              />
              <SliderRow
                label="Or at"
                value={thresholdPercent}
                min={25}
                max={100}
                step={5}
                format={v => `${v}%`}
                onChange={v => set('thresholdPercent', v)}
              />
            </>
          )}
          <div className="px-5 py-4">
            <button
              onClick={signOut}
              className="text-error text-[13px] font-[600] hover:text-error/80 transition-colors duration-150"
            >
              Sign out
            </button>
          </div>
        </>
      )}
    </Section>
  )
}

function MalojaCard() {
  const { serverUrl, apiKey, enabled, set } = useMalojaStore()
  const [showEdit, setShowEdit] = useState(!serverUrl)
  const [draftUrl, setDraftUrl] = useState(serverUrl)
  const [draftKey, setDraftKey] = useState(apiKey)
  const [testing, setTesting] = useState(false)
  const [testFailed, setTestFailed] = useState(false)

  async function testAndSave() {
    setTesting(true)
    setTestFailed(false)
    try {
      await testMalojaConnection(draftUrl, draftKey)
      set('serverUrl', draftUrl)
      set('apiKey', draftKey)
      set('enabled', true)
      toast('Connected to Maloja')
      setShowEdit(false)
    } catch {
      setTestFailed(true)
    } finally {
      setTesting(false)
    }
  }

  return (
    <Section title="Maloja" icon="dns">
      {showEdit ? (
        <div className="px-5 py-4 space-y-3">
          <ModalTextField value={draftUrl} onChange={setDraftUrl} placeholder="https://maloja.example.com" />
          <ModalTextField value={draftKey} onChange={setDraftKey} placeholder="API key" />
          {testFailed && (
            <p className="text-[12px] text-error">
              Couldn't connect — check the URL and API key, and that this Maloja server allows
              requests from this site (CORS).
            </p>
          )}
          <div className="flex gap-2">
            <ModalButton label={testing ? 'Testing…' : 'Test & Save'} onClick={testAndSave} disabled={testing || !draftUrl || !draftKey} />
            {serverUrl && (
              <ModalButton label="Cancel" tonal onClick={() => { setDraftUrl(serverUrl); setDraftKey(apiKey); setShowEdit(false) }} />
            )}
          </div>
        </div>
      ) : (
        <>
          <InfoRow label="Server" value={serverUrl} />
          <SwitchRow label="Enable scrobbling" checked={enabled} onChange={v => set('enabled', v)} />
          <div className="px-5 py-4 flex gap-4">
            <button
              onClick={() => setShowEdit(true)}
              className="text-primary text-[13px] font-[600] hover:text-primary/80 transition-colors duration-150"
            >
              Edit
            </button>
            <button
              onClick={() => { set('serverUrl', ''); set('apiKey', ''); set('enabled', false); setDraftUrl(''); setDraftKey(''); setShowEdit(true) }}
              className="text-error text-[13px] font-[600] hover:text-error/80 transition-colors duration-150"
            >
              Disconnect
            </button>
          </div>
        </>
      )}
    </Section>
  )
}

/* ─── Data & Backup ──────────────────────────────────────── */

function DataBackupCard() {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [importing, setImporting] = useState(false)
  const [showClearHistory, setShowClearHistory] = useState(false)

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setImporting(true)
    try {
      await importBackupFile(file)
      toast('Backup restored — reload the page to see everything applied')
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Couldn\'t import that file')
    } finally {
      setImporting(false)
    }
  }

  return (
    <Section title="Data & Backup" icon="storage">
      <CustomRow
        label="Export backup"
        description="Downloads a JSON file with your settings and local listening history. Contains any saved tokens — keep it private."
      >
        <ModalButton label="Export" tonal onClick={downloadBackup} />
      </CustomRow>
      <CustomRow
        label="Import backup"
        description="Downloaded song files aren't included in a restore — only which ones you'd downloaded."
      >
        <input ref={fileInputRef} type="file" accept="application/json" onChange={handleImportFile} className="hidden" />
        <ModalButton
          label={importing ? 'Importing…' : 'Choose file'}
          tonal
          disabled={importing}
          onClick={() => fileInputRef.current?.click()}
        />
      </CustomRow>
      <div className="px-5 py-4">
        <button
          onClick={() => setShowClearHistory(true)}
          className="text-[13px] font-[600] text-error hover:text-error/80 transition-colors duration-150"
        >
          Clear listening history
        </button>
      </div>
      <Modal
        open={showClearHistory}
        onClose={() => setShowClearHistory(false)}
        title="Clear listening history?"
        footer={
          <>
            <ModalButton label="Cancel" tonal onClick={() => setShowClearHistory(false)} />
            <ModalButton
              label="Clear"
              onClick={() => { clearHistoryLog(); setShowClearHistory(false); toast('Listening history cleared') }}
            />
          </>
        }
      >
        <p className="text-[13px] text-on-surface-var">
          This clears the local play history behind the Statistics page's trend charts and streaks on this device.
          It can't be undone.
        </p>
      </Modal>
    </Section>
  )
}

/* ─── Page ───────────────────────────────────────────────── */

export function SettingsPage() {
  const { serverUrl, username, logout } = useAuthStore()
  const s = useSettingsStore()

  function handleEqChange(preset: EqPreset) {
    s.set('equalizerPreset', preset)
    applyEqPreset(preset)
  }

  function handleSpeedChange(speed: PlaybackSpeed) {
    s.set('playbackSpeed', speed)
    audio.playbackRate = speed
  }

  return (
    <div className="flex-1 overflow-y-auto page-enter">
      <div className="max-w-2xl mx-auto px-6 py-6 pb-16 space-y-8">
        <div>
          <h1 className="text-[26px] font-[700] text-on-surface tracking-[-0.5px]">Settings</h1>
          <p className="text-[13px] text-on-surface-var mt-1">Personalize your Resonance experience</p>
        </div>

        {/* ── Appearance ── */}
        <Section title="Appearance" icon="palette">
          <CustomRow label="Accent color" description="Choose your theme color. Applied everywhere instantly.">
            <ThemePicker value={s.accentTheme} onChange={v => { s.set('accentTheme', v); s.set('customAccentColor', null) }} />
            <div className="flex items-center gap-2 mt-1">
              <input
                type="color"
                value={s.customAccentColor ?? themeColors[s.accentTheme]}
                onChange={e => s.set('customAccentColor', e.target.value)}
                className="w-8 h-8 rounded-full overflow-hidden border-2 border-outline-var/40 cursor-pointer bg-transparent"
              />
              <span className="text-[12px] text-on-surface-var">Custom color</span>
              {s.customAccentColor && (
                <button
                  onClick={() => s.set('customAccentColor', null)}
                  className="text-[12px] font-[600] text-primary hover:text-primary/80 transition-colors duration-150"
                >
                  Reset to preset
                </button>
              )}
            </div>
          </CustomRow>
          <ChipsRow
            label="Player artwork shape"
            options={[
              { label: 'Rounded', value: 'rounded' as PlayerArtworkShape },
              { label: 'Square', value: 'square' as PlayerArtworkShape },
              { label: 'Circle', value: 'circle' as PlayerArtworkShape },
            ]}
            value={s.playerArtworkShape}
            onChange={v => s.set('playerArtworkShape', v)}
          />
          <SwitchRow
            label="True black"
            description="Pure black background instead of the theme's dark surface — nice on OLED screens."
            icon="contrast"
            checked={s.trueBlack}
            onChange={v => s.set('trueBlack', v)}
          />
          <SwitchRow
            label="Compact lists"
            description="Tighter row spacing in song lists."
            icon="density_small"
            checked={s.compactList}
            onChange={v => s.set('compactList', v)}
          />
          <ChipsRow
            label="Animation speed"
            description="Controls how fast transitions and animations play."
            options={[
              { label: 'Slow', value: 'slow' as AnimationSpeed, icon: 'speed' },
              { label: 'Normal', value: 'normal' as AnimationSpeed },
              { label: 'Fast', value: 'fast' as AnimationSpeed },
              { label: 'Off', value: 'off' as AnimationSpeed },
            ]}
            value={s.animationSpeed}
            onChange={v => s.set('animationSpeed', v)}
          />
          <ChipsRow
            label="Grid density"
            description="Column count and spacing for album and artist grids."
            options={[
              { label: 'Compact', value: 'compact' as GridDensity },
              { label: 'Comfortable', value: 'comfortable' as GridDensity },
              { label: 'Spacious', value: 'spacious' as GridDensity },
            ]}
            value={s.gridDensity}
            onChange={v => s.set('gridDensity', v)}
          />
          <SwitchRow
            label="Show play count"
            description="Display play counts in album and song views."
            icon="bar_chart"
            checked={s.showPlayCount}
            onChange={v => s.set('showPlayCount', v)}
          />
        </Section>

        {/* ── Player ── */}
        <Section title="Player" icon="play_circle">
          <ChipsRow
            label="Player background"
            description="Visual style for the fullscreen player."
            options={[
              { label: 'Blurred art', value: 'blur' as PlayerBackground, icon: 'blur_on' },
              { label: 'Gradient', value: 'gradient' as PlayerBackground, icon: 'gradient' },
              { label: 'Minimal', value: 'minimal' as PlayerBackground },
            ]}
            value={s.playerBackground}
            onChange={v => s.set('playerBackground', v)}
          />
          <SwitchRow
            label="Vinyl art rotation"
            description="Show album art as a spinning vinyl record in the fullscreen player."
            icon="album"
            checked={s.artRotation}
            onChange={v => s.set('artRotation', v)}
          />
          <SwitchRow
            label="Audio visualizer"
            description="Show animated frequency bars in the fullscreen player."
            icon="graphic_eq"
            checked={s.showVisualizer}
            onChange={v => s.set('showVisualizer', v)}
          />
          <ChipsRow
            label="Queue end behavior"
            description="What to do when the queue finishes."
            options={[
              { label: 'Stop', value: 'stop' as QueueEndBehavior },
              { label: 'Repeat all', value: 'repeat-all' as QueueEndBehavior, icon: 'repeat' },
            ]}
            value={s.queueEndBehavior}
            onChange={v => s.set('queueEndBehavior', v)}
          />
          <SwitchRow
            label="Smart Shuffle"
            description="Bias shuffle away from songs you've played recently, based on this device's listening history."
            icon="shuffle"
            checked={s.smartShuffle}
            onChange={v => s.set('smartShuffle', v)}
          />
        </Section>

        {/* ── Audio ── */}
        <Section title="Audio" icon="equalizer">
          <ChipsRow
            label="Audio quality"
            description="Maximum streaming bitrate. Original streams the file as-is."
            options={[
              { label: 'Original', value: 0 as AudioQuality },
              { label: '320 kbps', value: 320 as AudioQuality },
              { label: '192 kbps', value: 192 as AudioQuality },
              { label: '128 kbps', value: 128 as AudioQuality },
              { label: '96 kbps', value: 96 as AudioQuality },
            ]}
            value={s.maxBitRate}
            onChange={v => s.set('maxBitRate', v)}
          />
          <ChipsRow
            label="ReplayGain"
            description="Volume normalization applied by Navidrome during streaming."
            options={[
              { label: 'None', value: 'none' as ReplayGain },
              { label: 'Track', value: 'track' as ReplayGain },
              { label: 'Album', value: 'album' as ReplayGain },
            ]}
            value={s.replayGain}
            onChange={v => s.set('replayGain', v)}
          />
          <ChipsRow
            label="Playback speed"
            description="Sets audio.playbackRate. Applies to the current and future tracks."
            options={[
              { label: '0.5×', value: 0.5 as PlaybackSpeed },
              { label: '0.75×', value: 0.75 as PlaybackSpeed },
              { label: '1×', value: 1 as PlaybackSpeed },
              { label: '1.25×', value: 1.25 as PlaybackSpeed },
              { label: '1.5×', value: 1.5 as PlaybackSpeed },
              { label: '2×', value: 2 as PlaybackSpeed },
            ]}
            value={s.playbackSpeed}
            onChange={handleSpeedChange}
          />
          <SwitchRow
            label="Preserve pitch"
            description="Keep the pitch natural when playback speed isn't 1×, instead of chipmunk/slow-mo."
            icon="tune"
            checked={s.preservePitch}
            onChange={v => { s.set('preservePitch', v); applyPreservePitch(v) }}
          />
          <SliderRow
            label="Crossfade"
            description="Blend the end of one track into the next. Set to 0 to disable and preload gaplessly instead."
            value={s.crossfadeSeconds}
            min={0}
            max={12}
            step={1}
            format={v => v === 0 ? 'Off' : `${v}s`}
            onChange={v => s.set('crossfadeSeconds', v)}
          />
          <CustomRow label="Equalizer" description="Adjust the frequency response. Changes apply in real time.">
            <EqPresetPicker value={s.equalizerPreset} onChange={handleEqChange} />
          </CustomRow>
        </Section>

        {/* ── Scrobbling ── */}
        <Section title="Scrobbling" icon="radio_button_checked">
          <SwitchRow
            label="Navidrome scrobbling"
            description="Record plays in your Navidrome listening history."
            icon="history"
            checked={s.navidromeScrobbling}
            onChange={v => s.set('navidromeScrobbling', v)}
          />
          {s.navidromeScrobbling && (
            <ChipsRow
              label="Scrobble threshold"
              description="How much of a track must play before it's recorded."
              options={[
                { label: '30%', value: 30 as ScrobbleThreshold },
                { label: '50%', value: 50 as ScrobbleThreshold },
                { label: '80%', value: 80 as ScrobbleThreshold },
              ]}
              value={s.scrobbleThreshold}
              onChange={v => s.set('scrobbleThreshold', v)}
            />
          )}
        </Section>

        {/* ── Online Services ── */}
        <LastFmCard />
        <MalojaCard />
        <Section title="Metadata Enrichment" icon="auto_fix_high">
          <CustomRow
            label="Discogs API Token"
            description="Adds member lists and profile info to artist pages, alongside Navidrome's own Last.fm-sourced bio. Get one from discogs.com/settings/developers."
          >
            <ModalTextField
              value={s.discogsToken}
              onChange={v => s.set('discogsToken', v)}
              placeholder="Personal access token"
            />
          </CustomRow>
        </Section>
        <Section title="Internet Share" icon="ios_share">
          <CustomRow
            label="Relay server URL"
            description="Your own resonance-share relay (or a friend's). Leave blank to disable link sharing."
          >
            <ModalTextField
              value={s.relayServerUrl}
              onChange={v => s.set('relayServerUrl', v)}
              placeholder="https://share.example.com"
            />
          </CustomRow>
          <CustomRow label="Upload token">
            <ModalTextField
              value={s.relayUploadToken}
              onChange={v => s.set('relayUploadToken', v)}
              placeholder="Bearer token"
            />
          </CustomRow>
        </Section>

        {/* ── Library ── */}
        <Section title="Library" icon="library_music">
          <ChipsRow
            label="Default album sort"
            description="How the Albums page is sorted on first load."
            options={[
              { label: 'A – Z', value: 'alphabeticalByName' as DefaultSort },
              { label: 'Newest', value: 'newest' as DefaultSort },
              { label: 'By year', value: 'byYear' as DefaultSort },
              { label: 'Random', value: 'random' as DefaultSort },
            ]}
            value={s.defaultSort}
            onChange={v => s.set('defaultSort', v)}
          />
        </Section>

        {/* ── Home Screen ── */}
        <Section title="Home Screen Sections" icon="home">
          <SwitchRow
            label="Recently Added"
            checked={s.homeShowRecentlyAdded}
            onChange={v => s.set('homeShowRecentlyAdded', v)}
          />
          {s.homeShowRecentlyAdded && (
            <ChipsRow
              label="Recently Added count"
              options={[
                { label: '10', value: 10 as RecentlyAddedCount },
                { label: '20', value: 20 as RecentlyAddedCount },
                { label: '30', value: 30 as RecentlyAddedCount },
                { label: '50', value: 50 as RecentlyAddedCount },
              ]}
              value={s.homeRecentlyAddedCount}
              onChange={v => s.set('homeRecentlyAddedCount', v)}
            />
          )}
          <SwitchRow
            label="Most Played"
            checked={s.homeShowMostPlayed}
            onChange={v => s.set('homeShowMostPlayed', v)}
          />
          <SwitchRow
            label="Random Mix"
            checked={s.homeShowRandomMix}
            onChange={v => s.set('homeShowRandomMix', v)}
          />
        </Section>

        {/* ── Lyrics ── */}
        <Section title="Lyrics" icon="lyrics">
          <SwitchRow
            label="LRCLib fallback"
            description="Fetch synced lyrics from lrclib.net when your server doesn't have them."
            icon="public"
            checked={s.lrclibEnabled}
            onChange={v => s.set('lrclibEnabled', v)}
          />
          <ChipsRow
            label="Lyrics text size"
            options={[
              { label: 'Small', value: 'small' as LyricsSize },
              { label: 'Medium', value: 'medium' as LyricsSize },
              { label: 'Large', value: 'large' as LyricsSize },
            ]}
            value={s.lyricsSize}
            onChange={v => s.set('lyricsSize', v)}
          />
          <ChipsRow
            label="Lyrics alignment"
            options={[
              { label: 'Center', value: 'center' as LyricsAlign, icon: 'format_align_center' },
              { label: 'Left', value: 'left' as LyricsAlign, icon: 'format_align_left' },
            ]}
            value={s.lyricsAlign}
            onChange={v => s.set('lyricsAlign', v)}
          />
        </Section>

        {/* ── Sleep Timer ── */}
        <Section title="Sleep Timer" icon="bedtime">
          <SliderRow
            label="Default duration"
            description="Pre-fills the sleep timer's quick options in the fullscreen player. Doesn't start a timer by itself."
            value={s.sleepTimerMinutes}
            min={0}
            max={120}
            step={5}
            format={v => v === 0 ? 'Off' : `${v} min`}
            onChange={v => s.set('sleepTimerMinutes', v)}
          />
        </Section>

        <DataBackupCard />

        {/* ── Account ── */}
        <Section title="Account" icon="account_circle">
          <InfoRow label="Server" value={serverUrl} />
          <InfoRow label="Username" value={username} />
          <div className="px-5 py-4">
            <button
              onClick={logout}
              className="flex items-center gap-2 text-[14px] font-[500] text-error
                hover:text-error/80 transition-colors duration-150 active:scale-95"
            >
              <Icon name="logout" size={18} />
              Sign out
            </button>
          </div>
        </Section>

        {/* ── About ── */}
        <Section title="About" icon="info">
          <InfoRow label="Version" value="1.0.0" />
          <InfoRow label="API" value="Subsonic · Navidrome" />
          <InfoRow label="EQ" value="Web Audio API" />
          <a
            href="https://github.com/yuw1xx/resonance-app"
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center justify-between gap-4 px-5 py-4 hover:bg-on-surface/5 transition-colors duration-150"
          >
            <span className="text-[14px] text-on-surface-var">Source & license</span>
            <span className="flex items-center gap-1 text-[13px] text-primary font-[500]">
              GitHub
              <Icon name="open_in_new" size={14} />
            </span>
          </a>
        </Section>
      </div>
    </div>
  )
}
