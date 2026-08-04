interface BarChartEntry {
  label: string
  value: number
}

export function BarChart({
  entries, height = 120, formatValue,
}: { entries: BarChartEntry[]; height?: number; formatValue?: (v: number) => string }) {
  const max = Math.max(1, ...entries.map(e => e.value))
  // Percentage heights need a container with a *fixed* pixel height to resolve against —
  // reserving fixed-height label rows above/below keeps the bar itself from ever overflowing
  // the chart's declared `height`, regardless of how tall the value/day labels render.
  const barAreaHeight = Math.max(20, height - 32)

  return (
    <div className="flex items-end gap-1" style={{ height }}>
      {entries.map((e, i) => (
        <div key={i} className="flex-1 flex flex-col items-center gap-1 group min-w-0" style={{ height }}>
          <span className="h-3 leading-3 text-[9px] text-on-surface-var opacity-0 group-hover:opacity-100 transition-opacity duration-150 tabular-nums">
            {formatValue ? formatValue(e.value) : e.value}
          </span>
          <div className="w-full flex items-end" style={{ height: barAreaHeight }}>
            <div
              className="w-full rounded-t-[3px] bg-primary/60 group-hover:bg-primary transition-colors duration-150"
              style={{ height: `${Math.max(2, (e.value / max) * 100)}%` }}
            />
          </div>
          <span className="h-3 leading-3 text-[9px] text-outline truncate w-full text-center">{e.label}</span>
        </div>
      ))}
    </div>
  )
}
