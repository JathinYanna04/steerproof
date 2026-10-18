export function escapeHtml(str: string): string {
  if (!str) return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

export function toHeadline(str: string): string {
  const safe = escapeHtml(str);
  return safe.replace(/\*(.+?)\*/g, '<em class="accent">$1</em>');
}
