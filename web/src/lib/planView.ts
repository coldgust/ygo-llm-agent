/**
 * 顾问模式把「场面 / 方案比较 / 战术 / combo」拼进 thinking 文本，格式固定：
 *   ①[选] 这条线做什么 ｜ 收益：… ｜ 风险：… ｜ 为什么选它
 * 这里拆成结构化数据，才能把选中的那条凸显出来——原文一段话看不出重点。
 */
export type PlanOption = { mark: string; picked: boolean; line: string; gain: string; risk: string; why: string }
export type PlanView = { analysis: string; options: PlanOption[]; tactics: string; combo: string }

function parsePlanOption(line: string): PlanOption | null {
  const m = line.match(/^\s*([①②③④⑤])\s*\[(选|否)\]\s*(.*)$/)
  if (!m) return null
  const parts = m[3].split('｜').map((s) => s.trim()).filter(Boolean)
  let gain = ''
  let risk = ''
  let why = ''
  const main = parts.shift() ?? ''
  for (const part of parts) {
    if (part.startsWith('收益：')) gain = part.slice(3).trim()
    else if (part.startsWith('风险：')) risk = part.slice(3).trim()
    else why = why ? `${why} ${part}` : part
  }
  return { mark: m[1], picked: m[2] === '选', line: main, gain, risk, why }
}

export function parsePlanThinking(text: string): PlanView {
  const raw = (text ?? '').trim()
  const view: PlanView = { analysis: '', options: [], tactics: '', combo: '' }
  if (!raw) return view
  const iOpt = raw.indexOf('方案比较：')
  const iTac = raw.indexOf('战术：')
  const iCombo = raw.indexOf('combo：')

  // 场面段的终点取所有分段标记中最早出现的那个：顾问模式可能不输出方案比较，
  // 若只认方案比较，场面段会把战术/combo 整段吞进去
  const markers = [iOpt, iTac, iCombo].filter((i) => i >= 0)
  const headEnd = markers.length ? Math.min(...markers) : raw.length
  const head = raw.slice(0, headEnd)
  view.analysis = head.replace(/^场面：/, '').trim()

  if (iOpt >= 0) {
    const ends = [iTac, iCombo].filter((i) => i > iOpt)
    const end = ends.length ? Math.min(...ends) : raw.length
    view.options = raw
      .slice(iOpt + '方案比较：'.length, end)
      .split('\n')
      .map(parsePlanOption)
      .filter((o): o is PlanOption => o !== null)
  }
  if (iTac >= 0) {
    const end = iCombo > iTac ? iCombo : raw.length
    view.tactics = raw.slice(iTac + '战术：'.length, end).trim()
  }
  if (iCombo >= 0) view.combo = raw.slice(iCombo + 'combo：'.length).trim()
  return view
}
