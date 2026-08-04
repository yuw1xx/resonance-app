import { create } from 'zustand'
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
    <div className="fixed bottom-24 left-1/2 -translate-x-1/2 z-[70] flex flex-col items-center gap-2 pointer-events-none">
      {items.map(item => (
        <div
          key={item.id}
          className="flex items-center gap-2 px-4 py-2.5 rounded-full bg-inverse-surface text-inverse-on-surface
            text-[13px] font-[500] shadow-elevation-3 animate-fade-in"
        >
          <Icon name="info" size={16} className="opacity-80" />
          {item.message}
        </div>
      ))}
    </div>
  )
}
