import { NavLink, useLocation } from 'react-router-dom'
import { motion } from 'framer-motion'
import { useAuthStore } from '@/stores/auth'
import { Icon } from './Icon'
import { useRipple } from './Ripple'

const NAV = [
  { to: '/',           label: 'Home',       icon: 'home' },
  { to: '/songs',      label: 'Songs',      icon: 'music_note' },
  { to: '/albums',     label: 'Albums',     icon: 'album' },
  { to: '/artists',    label: 'Artists',    icon: 'people' },
  { to: '/playlists',  label: 'Playlists',  icon: 'queue_music' },
  { to: '/liked',      label: 'Liked Songs',icon: 'favorite' },
  { to: '/downloaded', label: 'Downloaded', icon: 'download_done' },
  { to: '/search',     label: 'Search',     icon: 'search' },
  { to: '/statistics', label: 'Statistics', icon: 'bar_chart' },
  { to: '/settings',   label: 'Settings',   icon: 'settings' },
]

// MD3 Navigation Drawer item (wide screens)
function DrawerItem({ to, label, icon }: { to: string; label: string; icon: string }) {
  const ripple = useRipple()
  return (
    <NavLink
      to={to}
      end={to === '/'}
      ref={ripple.ref as React.Ref<HTMLAnchorElement>}
      onPointerDown={ripple.onPointerDown}
      className={({ isActive }) =>
        `ripple-root relative flex items-center gap-3 px-4 h-[56px] rounded-full text-[14px]
        transition-colors duration-200 ease-md-standard
        ${isActive
          ? 'text-on-secondary-container font-[600]'
          : 'text-on-surface-var hover:text-on-surface hover:bg-on-surface/8 font-[500]'
        }`
      }
    >
      {({ isActive }) => (
        <>
          {isActive && (
            <motion.div
              layoutId="drawer-nav-pill"
              className="absolute inset-0 rounded-full bg-secondary-container"
              transition={{ type: 'spring', stiffness: 500, damping: 40 }}
            />
          )}
          <Icon name={icon} size={22} filled={isActive} className="relative flex-shrink-0" />
          <span className="relative tracking-[0.1px]">{label}</span>
        </>
      )}
    </NavLink>
  )
}

// MD3 Navigation Rail item (narrow screens)
function RailItem({ to, label, icon }: { to: string; label: string; icon: string }) {
  const ripple = useRipple()
  const { pathname } = useLocation()
  const isActive = to === '/' ? pathname === '/' : pathname.startsWith(to)

  return (
    <NavLink
      to={to}
      end={to === '/'}
      ref={ripple.ref as React.Ref<HTMLAnchorElement>}
      onPointerDown={ripple.onPointerDown}
      className="ripple-root flex flex-col items-center gap-1 py-2 rounded-2xl w-full
        transition-colors duration-200 ease-md-standard"
    >
      <div
        className={`relative w-14 h-8 rounded-full flex items-center justify-center transition-colors duration-200
          ${isActive ? '' : 'hover:bg-on-surface/8'}`}
      >
        {isActive && (
          <motion.div
            layoutId="rail-nav-pill"
            className="absolute inset-0 rounded-full bg-secondary-container"
            transition={{ type: 'spring', stiffness: 500, damping: 40 }}
          />
        )}
        <Icon name={icon} size={20} filled={isActive}
          className={`relative ${isActive ? 'text-on-secondary-container' : 'text-on-surface-var'}`} />
      </div>
      <span className={`text-[10px] font-[500] tracking-[0.3px]
        ${isActive ? 'text-on-secondary-container' : 'text-on-surface-var'}`}>
        {label}
      </span>
    </NavLink>
  )
}

export function Sidebar() {
  const logout = useAuthStore(s => s.logout)
  const rippleLogout = useRipple()

  return (
    <>
      {/* ── Navigation Drawer (md+) ──────────────────────── */}
      <aside className="hidden md:flex w-[240px] flex-shrink-0 bg-md-surface flex-col py-3 overflow-hidden">
        {/* Logo */}
        <div className="flex items-center gap-3 px-5 py-4 mb-1">
          <img src="/icon.svg" alt="" className="w-9 h-9 rounded-xl" />
          <span className="text-[18px] font-[700] text-on-surface tracking-[-0.3px]">Resonance</span>
        </div>

        {/* Nav */}
        <nav className="flex-1 px-3 space-y-0.5 overflow-y-auto">
          {NAV.map(n => <DrawerItem key={n.to} {...n} />)}
        </nav>

        {/* Sign out */}
        <div className="px-3 pt-2 border-t border-outline-var/20 mt-2">
          <button
            ref={rippleLogout.ref as React.Ref<HTMLButtonElement>}
            onPointerDown={rippleLogout.onPointerDown}
            onClick={logout}
            className="ripple-root flex items-center gap-3 px-4 h-[56px] rounded-full w-full
              text-[14px] font-[500] text-on-surface-var hover:text-on-surface hover:bg-on-surface/8
              transition-colors duration-200 ease-md-standard"
          >
            <Icon name="logout" size={22} filled={false} />
            <span>Sign out</span>
          </button>
        </div>
      </aside>

      {/* ── Navigation Rail (< md) ────────────────────────── */}
      <aside className="md:hidden flex flex-col w-[72px] flex-shrink-0 bg-md-surface py-3 overflow-hidden">
        {/* Logo mark */}
        <div className="flex justify-center mb-4 mt-2">
          <img src="/icon.svg" alt="" className="w-9 h-9 rounded-xl" />
        </div>

        {/* Nav */}
        <nav className="flex-1 flex flex-col items-center px-1.5 gap-0.5 overflow-y-auto">
          {NAV.map(n => <RailItem key={n.to} {...n} />)}
        </nav>

        {/* Sign out */}
        <div className="flex justify-center px-1.5 mt-2 pt-2 border-t border-outline-var/20">
          <button
            onClick={logout}
            className="w-14 h-14 flex flex-col items-center justify-center gap-1 rounded-2xl
              text-on-surface-var hover:bg-on-surface/8 transition-colors duration-150"
            title="Sign out"
          >
            <Icon name="logout" size={20} filled={false} />
            <span className="text-[9px] font-[500]">Out</span>
          </button>
        </div>
      </aside>
    </>
  )
}
