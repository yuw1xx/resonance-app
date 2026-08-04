interface IconProps {
  name: string
  size?: number
  filled?: boolean
  grade?: number
  className?: string
}

export function Icon({ name, size = 24, filled = true, grade = 0, className = '' }: IconProps) {
  return (
    <span
      className={`material-symbols-rounded select-none ${className}`}
      style={{
        fontSize: `${size}px`,
        lineHeight: 1,
        fontVariationSettings: `'FILL' ${filled ? 1 : 0}, 'wght' 400, 'GRAD' ${grade}, 'opsz' ${size}`,
      }}
      aria-hidden="true"
    >
      {name}
    </span>
  )
}
