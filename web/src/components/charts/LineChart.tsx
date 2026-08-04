interface LineChartPoint {
  label: string
  value: number
}

export function LineChart({ points, height = 120 }: { points: LineChartPoint[]; height?: number }) {
  if (points.length === 0) return null

  const width = Math.max(240, points.length * 14)
  const max = Math.max(1, ...points.map(p => p.value))
  const padY = 6
  const stepX = points.length > 1 ? width / (points.length - 1) : width

  const coords = points.map((p, i) => ({
    x: points.length > 1 ? i * stepX : width / 2,
    y: height - padY - (p.value / max) * (height - padY * 2),
  }))

  const linePath = coords.reduce((acc, c, i) => (i === 0 ? `M ${c.x},${c.y}` : `${acc} L ${c.x},${c.y}`), '')
  const lastX = coords[coords.length - 1]?.x ?? 0
  const areaPath = `${linePath} L ${lastX},${height} L 0,${height} Z`

  return (
    <div className="overflow-x-auto">
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="block min-w-full">
        <path d={areaPath} fill="var(--md-primary)" fillOpacity={0.12} stroke="none" />
        <path d={linePath} fill="none" stroke="var(--md-primary)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        {coords.map((c, i) => (
          <circle key={i} cx={c.x} cy={c.y} r={2} fill="var(--md-primary)" opacity={0.6} />
        ))}
      </svg>
    </div>
  )
}
