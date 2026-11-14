<script lang="ts">
  import { onMount } from 'svelte'
  import { api, type DuelEvent, type DuelView, type AdviceOption } from '../lib/api'
  import { statusLabel, TERMINAL_STATUSES } from '../lib/labels'
  import AdvicePanel from '../components/AdvicePanel.svelte'
  import DeckSource from '../components/DeckSource.svelte'
  import { persisted } from '../lib/persisted.svelte'

  // 开赛参数存 localStorage（persisted 赋值即写回）：同一浏览器不必每次重填。host/port 与 Combo 录入页共用。
  let host = persisted('ygo.host', '127.0.0.1')
  let port = persisted('ygo.port', 7911)
  let name = persisted('ygo.name', 'web-duel')
  let deckPath = persisted('ygo.deckPath', '')
  let archetype = persisted('ygo.archetype', '')
  let archetypes = $state<string[]>([])
  let move = persisted<'first' | 'second'>('ygo.move', 'second', (raw) =>
    raw === 'first' || raw === 'second' ? raw : undefined,
  )
  let deckYdkText = $state('')

  /**
   * 卡组思路：用户手写的打法（展开路线 / 关键 combo / 留牌偏好 / 禁忌操作）。
   * 会随开赛请求发给后端，注入规划器与决策器判断的上下文作为重点参考。
   */
  let deckStrategy = persisted('ygo.deckStrategy', '')
  // 初始展开状态按"首次读取的值"决定即可，不需要随输入变化
  let strategyOpen = $state(localStorage.getItem('ygo.deckStrategy') == null ? true : false)

  /** 对局库入库策略：本局结束时怎么处置复盘。 */
  const ARCHIVE_OPTIONS: { value: string; label: string }[] = [
    { value: 'ask', label: '结束后问我' },
    { value: 'auto', label: '模型判定' },
    { value: 'always', label: '全部入库' },
    { value: 'never', label: '全部不入库' },
  ]
  let archivePolicy = persisted<'ask' | 'auto' | 'always' | 'never'>(
    'ygo.archivePolicy',
    'ask',
    (raw) =>
      ARCHIVE_OPTIONS.some((o) => o.value === raw)
        ? (raw as 'ask' | 'auto' | 'always' | 'never')
        : undefined,
  )
  let archiveBusy = $state(false)
  let archiveError = $state('')

  let duel: DuelView | null = $state(null)
  let events: DuelEvent[] = $state([])
  let adviceId = $state('')
  let adviceTurn = $state(0)
  let adviceStale = $state(false)
  /** 规划器产出当前建议的 checkpoint：anchor（回合锚点）/ replan（决策器要求重规划） */
  let adviceCheckpoint = $state('')
  /** 旧建议已被决策器判决作废（ADVICE_REPLAN_REQUESTED）：面板置灰等待重规划发布 */
  let adviceRetired = $state(false)
  let adviceConf = $state(0)
  let adviceStrategy = $state('')
  let adviceAnalysis = $state('')
  let adviceResources = $state('')
  let adviceGoal = $state('')
  let adviceOptions = $state<AdviceOption[]>([])
  let adviceTactics = $state('')
  let adviceIntel = $state('')
  let adviceCombo = $state('')

  /** seq → 最终提交的操作摘要（后端 SUBMITTED 事件）。 */
  let submittedBySeq = $state<Record<number, string>>({})
  /** seq → 决策器随动作给出的落位区域（POSITION_HINT 事件，中文区名）。 */
  let positionHintBySeq = $state<Record<number, string>>({})
  let busy = $state(false)
  let error = $state('')
  let sseError = $state('')

  // 卡组主题只能从 combo 库里已有的主题里选；空 = 库里没有该主题的 combo
  onMount(() => {
    api.listArchetypes().then((list) => (archetypes = list)).catch(() => {})
  })

  /** 路径 → 样式类（旧名仍在，兼容历史 trace 回放）。 */
  const LEVEL_COLORS: Record<string, string> = {
    ZONE_HINT: 'ZONE_HINT',
    LLM_CALL: 'LLM_CALL', LLM_ONESHOT: 'LLM_CALL',
    COMBO: 'COMBO', TACTICAL: 'TACTICAL',
    PLAN_STEP: 'PLAN_STEP', PLAN_BRANCH: 'PLAN_BRANCH',
    FALLBACK: 'FALLBACK',
  }

  /**
   * 决策流分段。注意 ①唯一合法（ONLY_CHOICE）已被后端 WebDecisionFeed 从 web 出口过滤，
   * 前端永远收不到，不再展示该段；②决策器判断涵盖 LLM_CALL 及历史名（LLM_ONESHOT/PLAN_*）。
   */
  const STAGES: { key: string; label: string }[] = [
    { key: 'ZONE_HINT', label: '⓪位置兑现' },
    { key: 'LLM_CALL', label: '②决策器判断' },
    { key: 'FALLBACK', label: '③兜底' },
  ]

  /** 规划器（幕僚）事件子类型的徽标文案（枚举名不给使用者看）。 */
  const ADVICE_KIND_LABELS: Record<string, string> = {
    ADVICE_PUBLISHED: '发布建议',
    ADVICE_REPLAN_REQUESTED: '重规划请求',
    PARSE_FAILED: '解析失败',
    CHECKPOINT_FAILED: '调用失败',
  }

  /** checkpoint → 面板/事件行里的来源标签。 */
  const CHECKPOINT_LABELS: Record<string, string> = {
    anchor: '回合锚点',
    replan: '重规划',
  }

  function stageOf(level?: string): string {
    if (!level) return ''
    if (level === 'ZONE_HINT') return 'ZONE_HINT'
    if (['LLM_CALL', 'LLM_ONESHOT', 'PLAN_STEP', 'PLAN_BRANCH'].includes(level)) return 'LLM_CALL'
    if (['ONLY_CHOICE', 'REFLEX'].includes(level)) return '' // ① 被 web 出口过滤，正常不可见
    return 'FALLBACK'
  }

  function missList(e: DuelEvent): string[] {
    return String(e.missReasons ?? '').split(' | ').filter((x) => x.trim().length > 0)
  }

  /**
   * 连锁逐环拆分：后端 compactChain 形如「连锁：连锁1 我方「卡名」… -> 连锁2 对方「卡名」…」，
   * 一行挤不下时前端按分隔符拆成节点，每环独立成块，并按 发动方（我方/对方）上色。
   */
  function chainNodes(chain: string): { index: string; side: 'self' | 'opp' | ''; negated: boolean; rest: string }[] {
    return String(chain)
      .replace(/^连锁：/, '')
      .split(' -> ')
      .filter((node) => node.trim().length > 0)
      .map((node) => {
        const m = node.match(/^(连锁\d+)\s*([\s\S]*)$/)
        const index = m ? m[1] : ''
        const rest = m ? m[2] : node
        return {
          index,
          side: rest.includes('我方') ? 'self' : rest.includes('对方') ? 'opp' : '',
          negated: rest.includes('已被无效'),
          rest,
        }
      })
  }

  let stopSse: (() => void) | null = null

  /** v6 执行器已发过精简事件的决策序号（同 seq 的 DECISION 是它的原始扇出，可丢）。 */
  const execSeqs = new Set<number>()
  /** 已渲染过的事件键：EventSource 自动重连会整段回放历史，用它去重。 */
  const seenEvents = new Set<string>()

  /** 去重键；无法稳定标识的事件返回 null（不参与去重，允许重复出现）。 */
  function dedupeKey(e: DuelEvent): string | null {
    if (e.kind === 'connected') return 'connected'
    if (e.seq !== undefined) return `${e.kind}#${e.seq}`
    if (e.kind === 'DUEL_STATUS') return `DUEL_STATUS#${e.status ?? ''}`
    return null
  }

  function onEvent(e: DuelEvent) {
    // "最终提交了什么"挂到对应决策行上，不单独占一行
    if (e.kind === 'SUBMITTED') {
      if (e.seq !== undefined && e.submitted) {
        submittedBySeq = { ...submittedBySeq, [e.seq]: String(e.submitted) }
      }
      return
    }
    if (e.kind === 'EXEC_DECISION' && e.seq !== undefined) execSeqs.add(e.seq)
    if (e.kind === 'DECISION' && e.seq !== undefined && execSeqs.has(e.seq)) return

    const key = dedupeKey(e)
    if (key !== null) {
      if (seenEvents.has(key)) return
      seenEvents.add(key)
    }

    if (e.kind === 'ADVICE_EVENT' && e.adviceKind === 'ADVICE_PUBLISHED') {
      adviceId = String(e.adviceId ?? '')
      adviceTurn = Number(e.turn ?? 0)
      adviceStale = false
      adviceCheckpoint = String(e.checkpoint ?? '')
      adviceRetired = false
      adviceConf = Number(e.confidence ?? 0)
      adviceStrategy = String(e.strategy ?? '')
      adviceAnalysis = String(e.analysis ?? '')
      adviceResources = String(e.resources ?? '')
      adviceGoal = String(e.goal ?? '')
      adviceOptions = e.options ?? []
      adviceTactics = String(e.tactics ?? '')
      adviceIntel = String(e.intel ?? '')
      adviceCombo = String(e.combo ?? '')
    }
    // 决策器判定规划不可实现/不合理/已偏离 → 后端已作废旧建议（AdvisorKeeper.retire）：
    // 面板置灰，等 ADVICE_PUBLISHED（checkpoint=replan）替换
    if (e.kind === 'ADVICE_EVENT' && e.adviceKind === 'ADVICE_REPLAN_REQUESTED') {
      adviceRetired = true
    }
    // 执行器每次决策带上当前建议标识，用它把「建议 → 决策」连起来
    if (e.kind === 'EXEC_DECISION') {
      if (e.adviceId) {
        adviceId = String(e.adviceId)
        adviceTurn = Number(e.adviceTurn ?? adviceTurn)
        adviceStale = Boolean(e.adviceStale ?? false)
        adviceGoal = String(e.adviceGoal ?? adviceGoal)
      }
    }
    // 决策器选动作时一并给出的落位区域：挂到产生它的决策行上（seq 同源）
    if (e.kind === 'POSITION_HINT' && e.seq !== undefined) {
      positionHintBySeq = { ...positionHintBySeq, [e.seq]: String(e.zone ?? '') }
      return
    }
    // 一局打完的统计（RUN_DONE 在终态 DUEL_STATUS 之前）：实时刷新已答/保底
    if (e.kind === 'RUN_DONE' && duel) {
      duel = { ...duel, answered: e.answered ?? null, fallbacks: e.fallbacks ?? null }
    }
    if (e.kind === 'DUEL_STATUS') {
      const status = String(e.status ?? '')
      // 终态事件带统计、胜负与入库决定状态（ask 模式此刻应为 PENDING，前端据此弹询问）
      const patch: Partial<DuelView> = {}
      if (e.answered !== undefined) patch.answered = e.answered
      if (e.fallbacks !== undefined) patch.fallbacks = e.fallbacks
      if (e.winner !== undefined) patch.winner = e.winner
      if (e.reason !== undefined) patch.reason = e.reason
      if (e.archiveState !== undefined) patch.archiveState = e.archiveState
      if (e.archivePolicy !== undefined) patch.archivePolicy = e.archivePolicy
      if (duel) duel = { ...duel, status, ...patch }
      if (TERMINAL_STATUSES.includes(status)) {
        stopSse?.()
        stopSse = null
      }
    }
    events = [e, ...events].slice(0, 80)
  }

  async function start() {
    const hasPath = !!deckPath.current.trim()
    const hasYdk = !!deckYdkText.trim()
    if (hasPath === hasYdk) {
      error = hasPath
        ? '卡组路径与 ydk 文本（粘贴 / 文件载入）只能二选一，请清空其一'
        : '需要提供卡组：填写卡组路径，或粘贴 / 从文件载入 .ydk'
      return
    }
    busy = true
    error = ''
    sseError = ''
    events = []
    execSeqs.clear()
    seenEvents.clear()
    adviceId = ''
    adviceTurn = 0
    adviceStale = false
    adviceCheckpoint = ''
    adviceRetired = false
    adviceConf = 0
    adviceStrategy = ''
    adviceAnalysis = ''
    adviceResources = ''
    adviceGoal = ''
    adviceOptions = []
    adviceTactics = ''
    adviceIntel = ''
    adviceCombo = ''
    submittedBySeq = {}
    positionHintBySeq = {}
    try {
      duel = await api.startDuel({
        host: host.current, port: port.current, name: name.current,
        move: move.current,
        deckPath: deckPath.current.trim() || undefined,
        deckYdk: deckYdkText.trim() || undefined,
        archetype: archetype.current || undefined,
        deckStrategy: deckStrategy.current.trim() || undefined,
        archivePolicy: archivePolicy.current,
      })
      duel = await api.getDuel(duel.id)
      stopSse = api.subscribe(duel.id, onEvent, (m) => (sseError = m))
    } catch (e: any) {
      error = String(e.message ?? e)
    } finally {
      busy = false
    }
  }

  async function stop() {
    if (!duel) return
    await api.stopDuel(duel.id)
  }

  /** 「结束后问我」模式的答复：确认后后端在后台生成复盘并写对局库。 */
  async function archive(store: boolean) {
    if (!duel || archiveBusy) return
    archiveBusy = true
    archiveError = ''
    try {
      const updated = await api.archiveDuel(duel.id, store)
      duel = { ...duel, archiveState: updated.archiveState ?? null }
    } catch (e: any) {
      archiveError = String(e.message ?? e)
    } finally {
      archiveBusy = false
    }
  }

  function levelBadge(e: DuelEvent): string {
    if (e.kind === 'EXEC_DECISION' || e.kind === 'DECISION') return LEVEL_COLORS[e.level ?? e.pathKind ?? ''] ?? 'DUEL_STATUS'
    if (e.kind === 'ADVICE_EVENT') return 'PLAN_EVENT'
    if (e.kind === 'POSITION_HINT') return 'ZONE_HINT'
    if (e.kind === 'DUEL_STATUS') return 'DUEL_STATUS'
    if (e.kind === 'COMBO_RECORDED') return 'COMBO_RECORDED'
    return 'REFLEX'
  }

  function badgeText(e: DuelEvent): string {
    if (e.kind === 'ADVICE_EVENT') return ADVICE_KIND_LABELS[e.adviceKind ?? ''] ?? e.adviceKind ?? '建议'
    if (e.kind === 'DECISION' || e.kind === 'EXEC_DECISION') {
      // 后端会带 levelLabel（中文），历史事件回退到枚举名
      return e.levelLabel ?? e.pathLabel ?? e.level ?? e.pathKind ?? e.kind
    }
    if (e.kind === 'POSITION_HINT') return '位置预分配'
    if (e.kind === 'DUEL_STATUS') return '状态'
    if (e.kind === 'COMBO_RECORDED') return '知识入库'
    if (e.kind === 'connected') return '接入'
    return e.kind
  }

  function summarize(e: DuelEvent): string {
    // 请求描述与决策器输入对齐：优先用后端发的抬头（回合/谁的回合/阶段/"此时可发动效果"），
    // 历史事件回退到 requestLabel；抬头自带句号，拼进箭头句式时去掉
    const req = (ev: DuelEvent) =>
      (ev.requestHeadline || ev.requestLabel || ev.requestKind || '').replace(/。$/, '')
    const act = (ev: DuelEvent) => ev.intentLabel ?? ev.intent ?? ''
    if (e.kind === 'DECISION')
      return `${req(e)} → ${act(e)}${e.explanation ? ' — ' + e.explanation : ''}`
    if (e.kind === 'EXEC_DECISION') {
      const why = e.explanation ? ` — ${e.explanation}` : ''
      return `${req(e)} → ${act(e)}${why}`
    }
    if (e.kind === 'POSITION_HINT') return `${e.zone ?? ''}（下一步落位沿用，不再额外询问）`
    if (e.kind === 'ADVICE_EVENT') {
      if (e.adviceKind === 'ADVICE_PUBLISHED') {
        const src = CHECKPOINT_LABELS[e.checkpoint ?? '']
        const ms = e.elapsedMs ? `，${e.elapsedMs}ms` : ''
        return `${src ? src + '：' : ''}发布建议 ${e.adviceId}（置信 ${e.confidence}${ms}）：${e.goal ?? ''}`
      }
      if (e.adviceKind === 'ADVICE_REPLAN_REQUESTED')
        return `决策器判定规划失效，要求重规划${e.reason ? '：' + e.reason : ''}（旧建议已作废）`
      if (e.adviceKind === 'PARSE_FAILED')
        return `规划器输出解析失败（保持当前建议）${e.reason ? '：' + e.reason : ''}`
      if (e.adviceKind === 'CHECKPOINT_FAILED') return '规划器调用失败（冷却降级，期间决策器自行判断）'
      return e.adviceKind
    }
    if (e.kind === 'DUEL_STATUS') return `对局状态 → ${statusLabel(e.status)}`
    if (e.kind === 'COMBO_RECORDED')
      return `combo 提取完成：${e.count} 条 → 主题「${e.archetype ?? ''}」（PG combo_record#${e.recordId ?? '—'}）`
    if (e.kind === 'connected') return '已接入事件流，等待对局事件…'
    return JSON.stringify(e).slice(0, 120)
  }

  const terminal = $derived(duel && TERMINAL_STATUSES.includes(duel.status))
</script>

<form class="controls" onsubmit={(e) => { e.preventDefault(); start() }}>
  <label>地址 <input bind:value={host.current} size="12" /></label>
  <label>端口 <input bind:value={port.current} size="5" /></label>
  <label>名字 <input bind:value={name.current} size="14" /></label>
  <DeckSource bind:deckPath={deckPath.current} bind:deckYdk={deckYdkText} size={34} />
  <label>
    卡组主题
    <select bind:value={archetype.current}>
      <option value="">不选择</option>
      {#each archetypes as tag}
        <option value={tag}>{tag}</option>
      {/each}
    </select>
  </label>
  <label>
    先后手
    <select bind:value={move.current}>
      <option value="second">后手</option>
      <option value="first">先手</option>
    </select>
  </label>
  <label
    title="对局结束后的入库方式：结束后问我＝弹出询问；模型判定＝LLM 评估有无沉淀价值，值得才入库；全部入库 / 全部不入库＝不做判断">
    入库策略
    <select bind:value={archivePolicy.current}>
      {#each ARCHIVE_OPTIONS as o (o.value)}
        <option value={o.value}>{o.label}</option>
      {/each}
    </select>
  </label>
  <button class="go" type="submit" disabled={busy || !!duel && !terminal}>接入并对战</button>
  {#if duel && !terminal}
    <button type="button" onclick={stop}>停止</button>
  {/if}
</form>

<div class="strategy">
  <div class="strategyhead">
    <b>卡组思路</b>
    <span class="muted">
      写给 AI 的打法说明（展开路线、关键 combo、留牌偏好、禁忌操作）。会随开赛请求注入上下文，
      作为规划与每次决策的重点参考；与通用战术冲突时以这里为准。
    </span>
    <span class="count">{deckStrategy.current.length} 字</span>
    <button type="button" class="link" onclick={() => (strategyOpen = !strategyOpen)}>
      {strategyOpen ? '收起' : '展开'}
    </button>
  </div>
  {#if strategyOpen}
    <textarea
      class="strategybox"
      bind:value={deckStrategy.current}
      rows="7"
      placeholder={'例：闪刀是资源型卡组，不要为了打点把场面铺满。\n优先用交闪赚卡、再用抓锚/虎鲨清场；主怪区要留给零衣的自我解放。\n手坑优先断对手的检索与特召起点，别一次交完。\n禁忌：不要在墓地魔法不足 3 张时发抓锚的抢控制权效果。'}
    ></textarea>
    <div class="muted tiny">
      {#if duel && !terminal}改动从下一局生效（本局已在运行）{:else}留空则不注入{/if}
    </div>
  {/if}
</div>

{#if error}
  <p class="error">{error}</p>
{/if}

{#if sseError}
  <p class="error">{sseError}</p>
{/if}

<details style="margin-bottom: 12px">
  <summary class="muted">高级：直接粘贴 .ydk 文本（与卡组路径二选一；「从文件载入」的内容也在这里）</summary>
  <textarea bind:value={deckYdkText} rows="4" style="width:100%; background:#0f1115; color:#d7dae0; border:1px solid #2a3245; border-radius:6px; font-family:inherit"></textarea>
</details>

{#if duel}
  <div class="chips">
    <span class="chip">状态 <b>{statusLabel(duel.status)}</b></span>
    <span class="chip">已答 <b>{duel.answered ?? '—'}</b></span>
    <span class="chip">保底 <b>{duel.fallbacks ?? '—'}</b></span>
    {#if terminal}
      <span class="chip">胜负 <b>{duel.winner ?? '—'} / {duel.reason ?? '—'}</b></span>
    {/if}
  </div>

  {#if adviceId}
    <AdvicePanel
      {adviceId}
      {adviceStale}
      {adviceTurn}
      checkpoint={adviceCheckpoint}
      retired={adviceRetired}
      strategy={adviceStrategy}
      analysis={adviceAnalysis}
      resources={adviceResources}
      goal={adviceGoal}
      options={adviceOptions}
      tactics={adviceTactics}
      intel={adviceIntel}
      combo={adviceCombo}
      {adviceConf}
    />
  {/if}

  {#if terminal}
    {#if duel.archivePolicy === 'ask' && duel.archiveState === 'PENDING'}
      <div class="askline">
        <span>本局已结束。是否把复盘写入对局库（供 AI 以后检索历史经验）？</span>
        <button type="button" class="yes" disabled={archiveBusy} onclick={() => archive(true)}>写入对局库</button>
        <button type="button" disabled={archiveBusy} onclick={() => archive(false)}>不写入</button>
        {#if archiveError}<span class="error">{archiveError}</span>{/if}
      </div>
    {:else if duel.archiveState === 'ACCEPTED'}
      <div class="muted">已确认入库：复盘正在后台生成并写入对局库（可用 search_duels 检索）。</div>
    {:else if duel.archiveState === 'DECLINED'}
      <div class="muted">已跳过：本局不写入对局库。</div>
    {:else if duel.archivePolicy === 'auto'}
      <div class="muted">对局已结束：复盘将由模型判定是否有入库价值，判定值得才写入对局库。</div>
    {:else if duel.archivePolicy === 'always'}
      <div class="muted">对局已结束：复盘将在后台自动写入对局库（可用 search_duels 检索）。</div>
    {:else if duel.archivePolicy === 'ask'}
      <div class="muted">对局已结束：复盘服务未启用，本局不写入对局库。</div>
    {:else}
      <div class="muted">对局已结束：本局按「不入库」处理，不写入对局库。</div>
    {/if}
  {/if}

  <div class="feed">
    {#each events as e (e)}
      <div class="entry">
        <span class="badge {levelBadge(e)}">{badgeText(e)}</span>
        {#if e.turn !== undefined}<span class="muted">T{e.turn}{#if e.phaseLabel}·{e.phaseLabel}{/if} </span>{/if}
        {summarize(e)}
        {#if e.kind === 'EXEC_DECISION'}
          {#if e.chain}
            <div class="chainline">
              {#each chainNodes(e.chain) as n, i (i)}
                <div class="chainnode" class:self={n.side === 'self'} class:opp={n.side === 'opp'} class:negated={n.negated}>
                  {#if n.index}<span class="chainidx">{n.index}</span>{/if}
                  <span class="chaintext">{n.rest}</span>
                </div>
              {/each}
            </div>
          {/if}
          <div class="pathrow">
            {#each STAGES as st (st.key)}
              <span class="stage" class:hit={stageOf(e.level) === st.key}>{st.label}</span>
            {/each}
            {#if e.seq !== undefined && positionHintBySeq[e.seq]}
              <span class="zonetag">落位 → {positionHintBySeq[e.seq]}</span>
            {/if}
            {#if e.confidence !== undefined}
              <span class="muted">置信 {Number(e.confidence).toFixed(2)}</span>
            {/if}
          </div>
          {#if e.seq !== undefined && submittedBySeq[e.seq]}
            <div class="submitted">→ 决策：{submittedBySeq[e.seq]}</div>
          {/if}
          {#if missList(e).length}
            <ul class="misslist">
              {#each missList(e) as m (m)}<li>{m}</li>{/each}
            </ul>
          {/if}
        {:else if e.missReasons}
          <div class="muted" style="margin-left: 52px">未命中: {e.missReasons}</div>
        {/if}
      </div>
    {:else}
      <div class="muted">等待事件…（建房并开始对局后，决策/规划器事件会实时出现在这里）</div>
    {/each}
  </div>
{:else}
  <div class="feed">
    <div class="entry muted">填好上方参数后点「接入并对战」。流程：YGOPro 建房（{host.current}:{port.current}）→ 这里接入 → 实时决策流。</div>
  </div>
{/if}

<style>
  .strategy {
    margin: 0 0 12px;
    padding: 8px 10px;
    border: 1px solid #2f4470;
    border-radius: 8px;
    background: #111725;
  }
  .strategyhead {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 8px;
    font-size: 12.5px;
  }
  .strategyhead b {
    color: #7aa2f7;
  }
  .strategyhead .count {
    margin-left: auto;
    color: #4b5468;
    font-size: 11.5px;
  }
  .strategy .link {
    background: none;
    border: none;
    color: #7aa2f7;
    cursor: pointer;
    padding: 0 2px;
    font-size: 12px;
  }
  .strategybox {
    width: 100%;
    margin-top: 6px;
    background: #0f1115;
    color: #d7dae0;
    border: 1px solid #2a3245;
    border-radius: 6px;
    padding: 6px 8px;
    font-family: inherit;
    font-size: 12.5px;
    line-height: 1.6;
    resize: vertical;
  }
  .strategybox:focus {
    outline: none;
    border-color: #7aa2f7;
  }
  .tiny {
    font-size: 11px;
    margin-top: 3px;
  }

  .askline {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin: 0 0 12px;
    padding: 8px 12px;
    border: 1px solid #3f5a34;
    border-radius: 8px;
    background: #121a12;
    color: #c7ccd8;
    font-size: 12.5px;
  }
  .askline button {
    background: #16211b;
    color: #9fb8a8;
    border: 1px solid #2f4a38;
    border-radius: 6px;
    padding: 4px 12px;
    cursor: pointer;
    font-family: inherit;
  }
  .askline button.yes {
    background: #17352a;
    color: #4fd68f;
    border-color: #2f6d4f;
  }
  .askline button:disabled {
    opacity: 0.5;
    cursor: default;
  }

  .pathrow {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    margin: 4px 0 0 52px;
    font-size: 11.5px;
  }
  .stage {
    color: #4b5468;
    border: 1px solid #232a38;
    border-radius: 10px;
    padding: 1px 8px;
  }
  .stage.hit {
    color: #0f1115;
    background: #7aa2f7;
    border-color: #7aa2f7;
    font-weight: 600;
  }
  .zonetag {
    color: #8fd6c0;
    border: 1px dashed #3a6a5f;
    border-radius: 10px;
    padding: 1px 8px;
  }
  /* 决策器看到的连锁逐环内容（连锁1 对方「卡名」…效果原文），与判断理由同源。
     每环独立成块：左缘色条区分发动方（我方绿 / 对方橙），已被无效的整环压暗。 */
  .chainline {
    margin: 4px 0 0 52px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    max-width: 760px;
  }
  .chainnode {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 4px 10px;
    border: 1px solid #26304a;
    border-left: 3px solid #7aa2f7;
    border-radius: 6px;
    background: #141a28;
    color: #9db8d9;
    font-size: 12px;
    line-height: 1.55;
  }
  .chainnode.self {
    border-left-color: #4fd68f;
  }
  .chainnode.opp {
    border-left-color: #e0af68;
  }
  .chainnode.negated {
    opacity: 0.55;
  }
  .chainidx {
    flex-shrink: 0;
    color: #7aa2f7;
    font-weight: 600;
    white-space: nowrap;
  }
  .chainnode.self .chainidx {
    color: #4fd68f;
  }
  .chainnode.opp .chainidx {
    color: #e0af68;
  }
  .chaintext {
    min-width: 0;
    word-break: break-word;
  }
  .submitted {
    margin: 3px 0 0 52px;
    color: #7fd1a3;
    font-size: 12.5px;
    font-weight: 600;
  }
  .misslist {
    margin: 3px 0 0 52px;
    padding-left: 16px;
    color: #6b7383;
    font-size: 11.5px;
  }
  .misslist li {
    line-height: 1.5;
  }
</style>
