import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { Icon } from './Icon'
import { useRipple } from './Ripple'

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  children: React.ReactNode
  footer?: React.ReactNode
  maxWidth?: number
}

export function Modal({ open, onClose, title, children, footer, maxWidth = 420 }: ModalProps) {
  const closeRipple = useRipple()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0, transition: { duration: 0.15 } }}
          className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-scrim/50"
          onClick={onClose}
          onPointerDown={e => e.stopPropagation()}
          onDoubleClick={e => e.stopPropagation()}
        >
          <motion.div
            onClick={e => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label={title}
            initial={{ opacity: 0, scale: 0.92, y: 12 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: 8, transition: { duration: 0.15 } }}
            transition={{ type: 'spring', stiffness: 400, damping: 32 }}
            className="w-full bg-surface-c rounded-3xl shadow-elevation-4 overflow-hidden"
            style={{ maxWidth }}
          >
            <div className="flex items-center justify-between px-6 pt-5 pb-2">
              <h2 className="text-[16px] font-[700] text-on-surface tracking-[-0.2px]">{title}</h2>
              <button
                ref={closeRipple.ref as React.Ref<HTMLButtonElement>}
                onPointerDown={closeRipple.onPointerDown}
                onClick={onClose}
                aria-label="Close"
                className="ripple-root w-8 h-8 rounded-full flex items-center justify-center
                  text-on-surface-var hover:bg-on-surface/8 transition-colors duration-150"
              >
                <Icon name="close" size={18} />
              </button>
            </div>
            <div className="px-6 py-3">{children}</div>
            {footer && <div className="flex justify-end gap-2 px-6 pb-5 pt-2">{footer}</div>}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  )
}

export function ModalButton({
  label, onClick, tonal = false, disabled = false,
}: { label: string; onClick: () => void; tonal?: boolean; disabled?: boolean }) {
  const r = useRipple()
  return (
    <button
      ref={r.ref as React.Ref<HTMLButtonElement>}
      onPointerDown={r.onPointerDown}
      onClick={onClick}
      disabled={disabled}
      className={`ripple-root px-5 py-2.5 rounded-full text-[13px] font-[600]
        transition-all duration-200 ease-md-standard hover:scale-[1.02] active:scale-[0.97]
        disabled:opacity-40 disabled:pointer-events-none
        ${tonal
          ? 'text-on-surface-var hover:bg-on-surface/8'
          : 'bg-primary text-on-primary shadow-elevation-2'
        }`}
    >
      {label}
    </button>
  )
}

export function ModalTextField({
  value, onChange, placeholder, autoFocus = false,
}: { value: string; onChange: (v: string) => void; placeholder?: string; autoFocus?: boolean }) {
  return (
    <input
      type="text"
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder={placeholder}
      autoFocus={autoFocus}
      className="w-full bg-surface-high border border-outline-var/30 rounded-2xl
        px-4 py-3 text-[14px] text-on-surface placeholder-outline
        focus:border-primary focus:ring-2 focus:ring-primary/15
        transition-all duration-200 ease-md-standard"
    />
  )
}
