import { toast } from '@/components/Toast'

export async function share(url: string, title: string) {
  if (navigator.share) {
    try {
      await navigator.share({ title, url })
    } catch (err) {
      // AbortError fires when the user just closes the native share sheet — not a failure.
      if (err instanceof Error && err.name === 'AbortError') return
      await copyToClipboard(url)
    }
    return
  }
  await copyToClipboard(url)
}

async function copyToClipboard(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    toast('Link copied to clipboard')
  } catch {
    toast('Couldn\'t share or copy the link')
  }
}
