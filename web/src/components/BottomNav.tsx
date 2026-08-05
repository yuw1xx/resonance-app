import { NavLink } from 'react-router-dom'
import { motion } from 'framer-motion'
import { NAV } from './Sidebar'
import { Icon } from './Icon'
import { useRipple } from './Ripple'

// Real phone widths (<sm) get a horizontally-scrollable bottom tab strip instead of the
// left-side icon rail — a fixed-width vertical rail eats a large share of a phone's already
// narrow viewport, where a bottom bar costs nothing horizontally. All nav items stay reachable
// by scrolling the strip rather than being cut down to a fixed handful behind an overflow menu.
function BottomNavItem({ to, label, icon }: { to: string; label: string; icon: string }) {
  const ripple = useRipple()
  return (
    <NavLink
      to={to}
      end={to === '/'}
      ref={ripple.ref as React.Ref<HTMLAnchorElement>}
      onPointerDown={ripple.onPointerDown}
      className="ripple-root relative flex flex-col items-center justify-center gap-0.5
        min-w-[64px] flex-shrink-0 py-2 snap-center"
    >
      {({ isActive }) => (
        <>
          {isActive && (
            <motion.div
              layoutId="bottom-nav-pill"
              className="absolute top-1 left-1/2 -translate-x-1/2 w-8 h-1 rounded-full bg-primary"
              transition={{ type: 'spring', stiffness: 500, damping: 40 }}
            />
          )}
          <Icon name={icon} size={22} filled={isActive}
            className={isActive ? 'text-primary' : 'text-on-surface-var'} />
          <span className={`text-[10px] font-[500] tracking-[0.2px] whitespace-nowrap
            ${isActive ? 'text-primary' : 'text-on-surface-var'}`}>
            {label}
          </span>
        </>
      )}
    </NavLink>
  )
}

export function BottomNav() {
  return (
    <nav
      className="sm:hidden flex-shrink-0 flex overflow-x-auto snap-x snap-mandatory
        bg-md-surface border-t border-outline-var/20"
      style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
    >
      {NAV.map(n => <BottomNavItem key={n.to} {...n} />)}
    </nav>
  )
}
