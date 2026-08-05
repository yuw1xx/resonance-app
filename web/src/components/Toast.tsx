import { create } from 'zustand'
import { AnimatePresence, motion } from 'framer-motion'
import { Icon } from './Icon'

interface ToastItem {
  id: number
  message: string
}

interface ToastState {
  items: ToastItem[]
}

let nextId = 0
const useToastStore = create<ToastState>(() => ({ items: [] }))

export function toast(message: string) {
  const id = nextId++
  useToastStore.setState(s => ({ items: [...s.items, { id, message }] }))
  setTimeout(() => {
    useToastStore.setState(s => ({ items: s.items.filter(i => i.id !== id) }))
  }, 3200)
}

export function ToastHost() {
  const items = useToastStore(s => s.items)
  if (!items.length) return null

  return (
    <div className="fixed bottom-48 sm:bottom-24 left-1/2 -translate-x-1/2 z-[70] flex flex-col items-center gap-2 pointer-events-none">
      <AnimatePresence>
        {items.map(item => (
          <motion.div
            key={item.id}
            layout
            initial={{ opacity: 0, y: 16, scale: 0.9 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -8, scale: 0.92, transition: { duration: 0.15 } }}
            transition={{ type: 'spring', stiffness: 500, damping: 32 }}
            className="flex items-center gap-2 px-4 py-2.5 rounded-full bg-inverse-surface text-inverse-on-surface
              text-[13px] font-[500] shadow-elevation-3"
          >
            <Icon name="info" size={16} className="opacity-80" />
            {item.message}
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
