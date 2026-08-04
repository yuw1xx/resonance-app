import type { AccentTheme } from '@/stores/settings'

interface ThemeTokens {
  primary: string
  onPrimary: string
  primaryContainer: string
  onPrimaryContainer: string
  secondaryContainer: string
  onSecondaryContainer: string
  bg: string
  surface: string
  surfaceLow: string
  surfaceC: string
  surfaceHigh: string
  surfaceHighest: string
}

const themes: Record<AccentTheme, ThemeTokens> = {
  purple: {
    primary: '#D0BCFF',
    onPrimary: '#381E72',
    primaryContainer: '#4F378B',
    onPrimaryContainer: '#EADDFF',
    secondaryContainer: '#4A4458',
    onSecondaryContainer: '#E8DEF8',
    bg: '#0F0D13',
    surface: '#141218',
    surfaceLow: '#1D1B20',
    surfaceC: '#211F26',
    surfaceHigh: '#2B2930',
    surfaceHighest: '#36343B',
  },
  blue: {
    primary: '#A8C8FF',
    onPrimary: '#003063',
    primaryContainer: '#1E4D8C',
    onPrimaryContainer: '#D6E3FF',
    secondaryContainer: '#3B4664',
    onSecondaryContainer: '#DBE2FF',
    bg: '#0D0F16',
    surface: '#111318',
    surfaceLow: '#191C23',
    surfaceC: '#1E2129',
    surfaceHigh: '#282B33',
    surfaceHighest: '#33363F',
  },
  green: {
    primary: '#84D994',
    onPrimary: '#00391A',
    primaryContainer: '#1A5430',
    onPrimaryContainer: '#A0F5B0',
    secondaryContainer: '#3A4E40',
    onSecondaryContainer: '#C6E8CE',
    bg: '#0C1210',
    surface: '#111512',
    surfaceLow: '#181E1A',
    surfaceC: '#1C231F',
    surfaceHigh: '#272D29',
    surfaceHighest: '#323834',
  },
  orange: {
    primary: '#FFB77A',
    onPrimary: '#4A2000',
    primaryContainer: '#6C3500',
    onPrimaryContainer: '#FFDCC2',
    secondaryContainer: '#533E2E',
    onSecondaryContainer: '#FFDCC2',
    bg: '#160F08',
    surface: '#1A130C',
    surfaceLow: '#221A12',
    surfaceC: '#281F17',
    surfaceHigh: '#342A21',
    surfaceHighest: '#3F352C',
  },
  pink: {
    primary: '#FFB0CA',
    onPrimary: '#5D1130',
    primaryContainer: '#7B2949',
    onPrimaryContainer: '#FFDCE7',
    secondaryContainer: '#51414A',
    onSecondaryContainer: '#FFDCE7',
    bg: '#160D10',
    surface: '#1A1115',
    surfaceLow: '#23191D',
    surfaceC: '#281D22',
    surfaceHigh: '#33282C',
    surfaceHighest: '#3E3337',
  },
  red: {
    primary: '#FFB3AE',
    onPrimary: '#680003',
    primaryContainer: '#930006',
    onPrimaryContainer: '#FFDAD7',
    secondaryContainer: '#5C3F3E',
    onSecondaryContainer: '#FFDAD7',
    bg: '#160D0D',
    surface: '#1A1110',
    surfaceLow: '#231817',
    surfaceC: '#291D1D',
    surfaceHigh: '#342828',
    surfaceHighest: '#3F3333',
  },
  teal: {
    primary: '#5DD5CB',
    onPrimary: '#003734',
    primaryContainer: '#00504C',
    onPrimaryContainer: '#74F2E7',
    secondaryContainer: '#354B4A',
    onSecondaryContainer: '#C2EEEC',
    bg: '#0A1312',
    surface: '#0E1716',
    surfaceLow: '#15201E',
    surfaceC: '#1A2523',
    surfaceHigh: '#253030',
    surfaceHighest: '#303B3A',
  },
}

function hexToChannels(hex: string): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `${r} ${g} ${b}`
}

function hexToHsl(hex: string): [number, number, number] {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l * 100]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h: number
  switch (max) {
    case r: h = ((g - b) / d + (g < b ? 6 : 0)); break
    case g: h = (b - r) / d + 2; break
    default: h = (r - g) / d + 4
  }
  return [h * 60, s * 100, l * 100]
}

function hslToHex(h: number, s: number, l: number): string {
  const sN = s / 100, lN = l / 100
  const c = (1 - Math.abs(2 * lN - 1)) * sN
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1))
  const m = lN - c / 2
  let [r, g, b] = [0, 0, 0]
  if (h < 60) [r, g, b] = [c, x, 0]
  else if (h < 120) [r, g, b] = [x, c, 0]
  else if (h < 180) [r, g, b] = [0, c, x]
  else if (h < 240) [r, g, b] = [0, x, c]
  else if (h < 300) [r, g, b] = [x, 0, c]
  else [r, g, b] = [c, 0, x]
  const toHex = (v: number) => Math.round((v + m) * 255).toString(16).padStart(2, '0')
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`
}

/** Approximates a full Material-You-style tonal palette from a single seed color via plain
 * HSL manipulation — not a true HCT/tonal-palette derivation (that needs a perceptual color
 * space library), but close enough to sit alongside the 7 hand-picked presets convincingly. */
function generateCustomTheme(hex: string): ThemeTokens {
  const [h] = hexToHsl(hex)
  return {
    primary: hex,
    onPrimary: hslToHex(h, 45, 18),
    primaryContainer: hslToHex(h, 38, 36),
    onPrimaryContainer: hslToHex(h, 60, 90),
    secondaryContainer: hslToHex(h, 12, 30),
    onSecondaryContainer: hslToHex(h, 25, 90),
    bg: hslToHex(h, 12, 7),
    surface: hslToHex(h, 10, 8),
    surfaceLow: hslToHex(h, 8, 11),
    surfaceC: hslToHex(h, 8, 13),
    surfaceHigh: hslToHex(h, 7, 17),
    surfaceHighest: hslToHex(h, 6, 21),
  }
}

export function applyTheme(theme: AccentTheme, customHex?: string | null) {
  const t = customHex ? generateCustomTheme(customHex) : themes[theme]
  const root = document.documentElement
  // Channel vars — Tailwind reads these for opacity modifiers (bg-primary/10 etc.)
  root.style.setProperty('--c-primary',               hexToChannels(t.primary))
  root.style.setProperty('--c-on-primary',            hexToChannels(t.onPrimary))
  root.style.setProperty('--c-primary-container',     hexToChannels(t.primaryContainer))
  root.style.setProperty('--c-on-primary-container',  hexToChannels(t.onPrimaryContainer))
  root.style.setProperty('--c-secondary-container',   hexToChannels(t.secondaryContainer))
  root.style.setProperty('--c-on-secondary-container',hexToChannels(t.onSecondaryContainer))
  root.style.setProperty('--c-bg',                    hexToChannels(t.bg))
  root.style.setProperty('--c-surface',               hexToChannels(t.surface))
  root.style.setProperty('--c-surface-low',           hexToChannels(t.surfaceLow))
  root.style.setProperty('--c-surface-c',             hexToChannels(t.surfaceC))
  root.style.setProperty('--c-surface-high',          hexToChannels(t.surfaceHigh))
  root.style.setProperty('--c-surface-highest',       hexToChannels(t.surfaceHighest))
  // Hex vars — used directly in CSS (slider track gradients, SVG strokes, backdrop filters)
  root.style.setProperty('--md-primary',           t.primary)
  root.style.setProperty('--md-primary-container', t.primaryContainer)
  root.style.setProperty('--md-background',        t.bg)
  root.style.setProperty('--md-surface-high',      t.surfaceHigh)
  root.style.setProperty('--md-surface-highest',   t.surfaceHighest)
}

/** Applied after applyTheme() when the user wants a true-black background (useful on OLED
 * screens) — only touches the two lowest surface tiers so cards/dialogs still read as
 * distinct elevation against pure black, rather than flattening the whole palette. */
export function applyTrueBlack(enabled: boolean) {
  const root = document.documentElement
  if (!enabled) return
  root.style.setProperty('--c-bg', '0 0 0')
  root.style.setProperty('--c-surface', '0 0 0')
  root.style.setProperty('--md-background', '#000000')
}

export const themeColors: Record<AccentTheme, string> = {
  purple: '#D0BCFF',
  blue:   '#A8C8FF',
  green:  '#84D994',
  orange: '#FFB77A',
  pink:   '#FFB0CA',
  red:    '#FFB3AE',
  teal:   '#5DD5CB',
}
