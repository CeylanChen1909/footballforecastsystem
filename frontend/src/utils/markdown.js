const escapeHtml = value => String(value ?? '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const safeUrl = value => {
  const url = String(value || '').trim()
  return /^(https?:|mailto:)/i.test(url) ? url : ''
}

const renderInline = value => {
  const codeTokens = []
  let output = escapeHtml(value)
  output = output.replace(/`([^`\n]+)`/g, (_, code) => {
    const token = `@@MARKDOWN_CODE_${codeTokens.length}@@`
    codeTokens.push(`<code>${code}</code>`)
    return token
  })
  output = output.replace(/!?\[([^\]]+)\]\(([^)\s]+)(?:\s+["']([^"']*)["'])?\)/g, (full, label, url, title) => {
    const safe = safeUrl(url)
    if (!safe) return label
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
    return `<a href="${escapeHtml(safe)}" target="_blank" rel="noopener noreferrer"${titleAttr}>${label}</a>`
  })
  output = output.replace(/\*\*([^*\n]+)\*\*|__([^_\n]+)__/g, (_, strongA, strongB) => `<strong>${strongA || strongB}</strong>`)
  output = output.replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
  output = output.replace(/\*([^*\n]+)\*|_([^_\n]+)_/g, (_, emA, emB) => `<em>${emA || emB}</em>`)
  output = output.replace(/@@MARKDOWN_CODE_(\d+)@@/g, (_, index) => codeTokens[Number(index)] || '')
  return output
}

/**
 * Small, dependency-free Markdown renderer for model replies.
 * HTML is escaped before formatting so model output cannot inject markup.
 */
export const renderMarkdown = source => {
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n')
  const blocks = []
  let paragraph = []
  let list = null
  let code = null
  let table = null

  const flushParagraph = () => {
    if (!paragraph.length) return
    blocks.push(`<p>${paragraph.map(renderInline).join('<br>')}</p>`)
    paragraph = []
  }
  const flushList = () => {
    if (!list) return
    blocks.push(`<${list.type}>${list.items.map(item => `<li>${renderInline(item)}</li>`).join('')}</${list.type}>`)
    list = null
  }
  const flushTable = () => {
    if (!table) return
    const head = table.headers.map(cell => `<th>${renderInline(cell)}</th>`).join('')
    const rows = table.rows.map(row => `<tr>${row.map(cell => `<td>${renderInline(cell)}</td>`).join('')}</tr>`).join('')
    blocks.push(`<div class="markdown-table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${rows}</tbody></table></div>`)
    table = null
  }
  const flushAll = () => {
    flushParagraph()
    flushList()
    flushTable()
  }

  lines.forEach((line, index) => {
    if (code) {
      if (/^\s*```/.test(line)) {
        blocks.push(`<pre><code>${escapeHtml(code.lines.join('\n'))}</code></pre>`)
        code = null
      } else {
        code.lines.push(line)
      }
      return
    }
    const splitTableRow = value => String(value).trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
    const isTableSeparator = value => /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(String(value || ''))
    if (!table && line.includes('|') && isTableSeparator(lines[index + 1])) {
      flushAll()
      table = { headers: splitTableRow(line), rows: [], separatorPending: true }
      return
    }
    if (table) {
      if (table.separatorPending && isTableSeparator(line)) { table.separatorPending = false; return }
      if (line.includes('|') && line.trim()) {
        table.rows.push(splitTableRow(line))
        return
      }
      flushTable()
    }
    const fence = line.match(/^\s*```(?:[\w+-]+)?\s*$/)
    if (fence) {
      flushAll()
      code = { lines: [] }
      return
    }
    if (!line.trim()) {
      flushAll()
      return
    }
    const heading = line.match(/^\s*(#{1,6})\s+(.+?)\s*#*\s*$/)
    if (heading) {
      flushAll()
      const level = heading[1].length
      blocks.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      return
    }
    const unordered = line.match(/^\s*[-*+]\s+(.+)$/)
    const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/)
    if (unordered || ordered) {
      flushParagraph()
      const type = unordered ? 'ul' : 'ol'
      if (!list || list.type !== type) {
        flushList()
        list = { type, items: [] }
      }
      list.items.push((unordered || ordered)[1])
      return
    }
    const quote = line.match(/^\s*>\s?(.*)$/)
    if (quote) {
      flushAll()
      blocks.push(`<blockquote>${renderInline(quote[1])}</blockquote>`)
      return
    }
    flushList()
    paragraph.push(line)
  })

  if (code) blocks.push(`<pre><code>${escapeHtml(code.lines.join('\n'))}</code></pre>`)
  flushAll()
  return blocks.join('')
}
