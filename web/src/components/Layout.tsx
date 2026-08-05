import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { BottomNav } from './BottomNav'
import { PlayerBar } from './player/PlayerBar'
import { FullscreenPlayer } from './player/FullscreenPlayer'
import { Queue } from './player/Queue'
import { usePlayerStore } from '@/stores/player'
import { useSettingsStore } from '@/stores/settings'
import { applyTheme, applyTrueBlack } from '@/lib/themes'
import { useSleepTimer } from '@/hooks/useSleepTimer'
import { Icon } from './Icon'
import { ToastHost } from './Toast'

function SleepTimerBadge({ remainingMs }: { remainingMs: number | null }) {
  if (remainingMs === null) return null
  const mins = Math.floor(remainingMs / 60_000)
  const secs = Math.floor((remainingMs % 60_000) / 1000)
  return (
    <div className="fixed bottom-48 sm:bottom-24 right-4 z-40 flex items-center gap-2 px-3 py-2 rounded-full
      bg-surface-high border border-outline-var/30 shadow-elevation-2 text-[12px] text-on-surface-var
      animate-fade-in">
      <Icon name="bedtime" size={14} className="text-primary" />
      <span className="tabular-nums font-[500]">
        {mins}:{String(secs).padStart(2, '0')}
      </span>
    </div>
  )
}

export function Layout() {
  const { showQueue, showFullscreen } = usePlayerStore()
  const { pathname } = useLocation()
  const { accentTheme, customAccentColor, animationSpeed, trueBlack } = useSettingsStore()
  const { remainingMs } = useSleepTimer()

  useEffect(() => {
    applyTheme(accentTheme, customAccentColor)
    applyTrueBlack(trueBlack)
  }, [accentTheme, customAccentColor, trueBlack])
  useEffect(() => { document.body.dataset.speed = animationSpeed }, [animationSpeed])

  return (
    <div className="flex flex-col h-full bg-md-bg">
      <div className="flex flex-1 overflow-hidden">
        <Sidebar />
        <div className="hidden sm:block w-px bg-outline-var/20 flex-shrink-0" />
        <main className="flex-1 flex overflow-hidden">
          <div key={pathname} className="flex-1 flex flex-col overflow-hidden">
            <Outlet />
          </div>
          {showQueue && <Queue />}
        </main>
      </div>
      <PlayerBar />
      <BottomNav />
      {showFullscreen && <FullscreenPlayer />}
      <SleepTimerBadge remainingMs={remainingMs} />
      <ToastHost />
    </div>
  )
}
