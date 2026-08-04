import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Outfit', '-apple-system', 'BlinkMacSystemFont', 'sans-serif'],
      },
      colors: {
        // Theme-sensitive: use CSS channel vars so opacity modifiers work (bg-primary/10 etc.)
        // and so applyTheme() instantly repaints the whole UI
        primary:                'rgb(var(--c-primary) / <alpha-value>)',
        'on-primary':           'rgb(var(--c-on-primary) / <alpha-value>)',
        'primary-container':    'rgb(var(--c-primary-container) / <alpha-value>)',
        'on-primary-container': 'rgb(var(--c-on-primary-container) / <alpha-value>)',
        'secondary-container':  'rgb(var(--c-secondary-container) / <alpha-value>)',
        'on-secondary-container':'rgb(var(--c-on-secondary-container) / <alpha-value>)',
        // Surface colors (also themed, subtle per-theme tint)
        'md-bg':                'rgb(var(--c-bg) / <alpha-value>)',
        'md-surface':           'rgb(var(--c-surface) / <alpha-value>)',
        'surface-low':          'rgb(var(--c-surface-low) / <alpha-value>)',
        'surface-c':            'rgb(var(--c-surface-c) / <alpha-value>)',
        'surface-high':         'rgb(var(--c-surface-high) / <alpha-value>)',
        'surface-highest':      'rgb(var(--c-surface-highest) / <alpha-value>)',
        // Non-themed (hardcoded — Tailwind handles opacity automatically for hex values)
        secondary:              '#CCC2DC',
        'on-secondary':         '#332D41',
        tertiary:               '#EFB8C8',
        'tertiary-container':   '#633B48',
        'on-tertiary-container':'#FFD8E4',
        error:                  '#F2B8B5',
        'error-container':      '#8C1D18',
        'on-error-container':   '#F9DEDC',
        'on-surface':           '#E6E1E5',
        'on-surface-var':       '#CAC4D0',
        outline:                '#938F99',
        'outline-var':          '#49454F',
        'inverse-surface':      '#E6E1E5',
        'inverse-on-surface':   '#313033',
        'inverse-primary':      '#6750A4',
        scrim:                  '#000000',
      },
      borderRadius: {
        'xs': '4px',
        'sm': '8px',
        'md': '12px',
        'lg': '16px',
        'xl': '28px',
        'full': '9999px',
      },
      boxShadow: {
        'elevation-1': '0 1px 2px rgba(0,0,0,0.3), 0 1px 3px 1px rgba(0,0,0,0.15)',
        'elevation-2': '0 1px 2px rgba(0,0,0,0.3), 0 2px 6px 2px rgba(0,0,0,0.15)',
        'elevation-3': '0 4px 8px 3px rgba(0,0,0,0.15), 0 1px 3px rgba(0,0,0,0.3)',
        'elevation-4': '0 6px 10px 4px rgba(0,0,0,0.15), 0 2px 3px rgba(0,0,0,0.3)',
        'elevation-5': '0 8px 12px 6px rgba(0,0,0,0.15), 0 4px 4px rgba(0,0,0,0.3)',
      },
      keyframes: {
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(12px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'slide-up': {
          from: { transform: 'translateY(100%)', opacity: '0' },
          to: { transform: 'translateY(0)', opacity: '1' },
        },
        'slide-in-right': {
          from: { transform: 'translateX(20px)', opacity: '0' },
          to: { transform: 'translateX(0)', opacity: '1' },
        },
        'scale-in': {
          from: { transform: 'scale(0.94)', opacity: '0' },
          to: { transform: 'scale(1)', opacity: '1' },
        },
        'ripple': {
          from: { transform: 'scale(0)', opacity: '0.12' },
          to: { transform: 'scale(4)', opacity: '0' },
        },
        'spin': {
          to: { transform: 'rotate(360deg)' },
        },
        'shimmer': {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        'grow-x': {
          from: { transform: 'scaleX(0)' },
          to: { transform: 'scaleX(1)' },
        },
        'float': {
          '0%,100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-4px)' },
        },
      },
      animation: {
        'fade-in':        'fade-in 250ms cubic-bezier(0.05, 0.7, 0.1, 1.0) forwards',
        'fade-up':        'fade-up 350ms cubic-bezier(0.05, 0.7, 0.1, 1.0) forwards',
        'slide-up':       'slide-up 450ms cubic-bezier(0.05, 0.7, 0.1, 1.0) forwards',
        'slide-in-right': 'slide-in-right 300ms cubic-bezier(0.05, 0.7, 0.1, 1.0) forwards',
        'scale-in':       'scale-in 250ms cubic-bezier(0.05, 0.7, 0.1, 1.0) forwards',
        'ripple':         'ripple 600ms linear forwards',
        'spin':           'spin 800ms linear infinite',
        'shimmer':        'shimmer 1.6s ease-in-out infinite',
        'float':          'float 3s ease-in-out infinite',
      },
      transitionTimingFunction: {
        'md-standard':            'cubic-bezier(0.2, 0, 0, 1)',
        'md-emphasized':          'cubic-bezier(0.2, 0, 0, 1)',
        'md-emphasized-decel':    'cubic-bezier(0.05, 0.7, 0.1, 1.0)',
        'md-emphasized-accel':    'cubic-bezier(0.3, 0.0, 0.8, 0.15)',
      },
      transitionDuration: {
        '50': '50ms',
        '150': '150ms',
        '250': '250ms',
        '350': '350ms',
        '450': '450ms',
      },
    },
  },
  plugins: [],
} satisfies Config
