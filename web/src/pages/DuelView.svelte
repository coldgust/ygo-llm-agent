<script lang="ts">
  import { onMount } from 'svelte'
  import { api, type DuelEvent, type DuelRequest, type DuelView, type StepDetail, type BranchDetail } from '../lib/api'
  import { statusLabel, TERMINAL_STATUSES } from '../lib/labels'
  import PlanPanel from '../components/PlanPanel.svelte'

  let host = $state('127.0.0.1')
  let port = $state(7911)
  let name = $state('web-duel')
  let deckPath = $state('')
  let archetype = $state('')
  let archetypes = $state<string[]>([])
  let move = $state<'first' | 'second'>('second')
  let deckYdkText = $state('')

  /**
   * 卡组思路：用户手写的打法（展开路线 / 关键 combo / 留牌偏好 / 禁忌操作）。
   * 会随开赛请求发给后端，注入规划器与决策器判断的上下文作为重点参考。
   * 存 localStorage：同一套卡组不必每次重打。
   */
  let deckStrategy = $state(localStorage.getItem('ygo.deckStrategy') ?? '')
  // 初始展开状态按"首次读取的值"决定即可，不需要随输入变化（这里显式取值，避免 svelte 的 state_referenced_locally 告警）
  let strategyOpen = $state(localStorage.getItem('ygo.deckStrategy') == null ? true : false)
  $effect(() => {
    localStorage.setItem('ygo.deckStrategy', deckStrategy)
  })

  /** 对局库入库策略：本局结束时怎么处置复盘。存 localStorage，同一浏览器记住选择。 */
  const ARCHIVE_OPTIONS: { value: string; label: string }[] = [
    { value: 'ask', label: '结束后问我' },
    { value: 'auto', label: '模型判定' },
    { value: 'always', label: '全部入库' },
    { value: 'never', label: '全部不入库' },
  ]
  const savedPolicy = localStorage.getItem('ygo.archivePolicy')
  let archivePolicy = $state(
    ARCHIVE_OPTIONS.some((o) => o.value === savedPolicy) ? savedPolicy! : 'ask',
  )
  $effect(() => {
    localStorage.setItem('ygo.archivePolicy', archivePolicy)
  })
  let archiveBusy = $state(false)
  let archiveError = $state('')

  let duel: DuelView | null = $state(null)
  let events: DuelEvent[] = $state([])
  let planId = $state('')
  let planThinking = $state('')
  let planGoal = $state('')
  let planConf = $state(0)
  let planCursor = $state(0)
  let planSteps = $state<StepDetail[]>([])
  let planBranches = $state<BranchDetail[]>([])

  /** seq → 最终提交的操作摘要（后端 SUBMITTED 事件）。 */
  let submittedBySeq = $state<Record<number, string>>({})
  let busy = $state(false)
  let error = $state('')
  let sseError = $state('')

  // 卡组主题只能从 combo 库里已有的主题里选；空 = 库里没有该主题的 combo
  onMount(() => {
    api.listArchetypes().then((list) => (archetypes = list)).catch(() => {})
  })

  /** 路径 → 样式类（旧名仍在，兼容历史 trace 回放）。 */
  const LEVEL_COLORS: Record<string, string> = {
    ONLY_CHOICE: 'ONLY_CHOICE', REFLEX: 'ONLY_CHOICE',
    PLAN_STEP: 'PLAN_STEP', PLAN_EXACT: 'PLAN_STEP',
    PLAN_BRANCH: 'PLAN_BRANCH', PLAN_SIMILAR: 'PLAN_BRANCH',
    LLM_CALL: 'LLM_CALL', LLM_ONESHOT: 'LLM_CALL',
    TACTICAL: 'TACTICAL', FALLBACK: 'FALLBACK', COMBO: 'COMBO',
  }

  /** 执行器的五级流水；命中哪一级就高亮哪一级，前面的都算"没接住"。 */
  const STAGES: { key: string; label: string }[] = [
    { key: 'ONLY_CHOICE', label: '①没得选' },
    { key: 'PLAN', label: '②照计划' },
    { key: 'LLM_CALL', label: '④决策器判断' },
    { key: 'FALLBACK', label: '⑤兜底' },
  ]

  /** 规划器事件子类型的徽标文案（枚举名不给使用者看）。 */
  const PLAN_KIND_LABELS: Record<string, string> = {
    PLAN_PUBLISHED: '发布计划',
    KEEP: '计划维持',
    PARSE_FAILED: '解析失败',
    PLAN_EMPTY: '空输出',
    CHECKPOINT_FAILED: '存档失败',
  }

  function stageOf(level?: string): string {
    if (!level) return ''
    if (level === 'ONLY_CHOICE' || level === 'REFLEX') return 'ONLY_CHOICE'
    if (['PLAN_STEP', 'PLAN_BRANCH', 'COMBO', 'PLAN_EXACT', 'PLAN_SIMILAR'].includes(level)) return 'PLAN'
    if (level === 'LLM_CALL' || level === 'LLM_ONESHOT') return 'LLM_CALL'
    return 'FALLBACK'
  }

  function missList(e: DuelEvent): string[] {
    return String(e.missReasons ?? '').split(' | ').filter((x) => x.trim().length > 0)
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

    if (e.kind === 'PLAN_EVENT' && e.planKind === 'PLAN_PUBLISHED') {
      planId = String(e.planId ?? '')
      planThinking = String(e.thinking ?? '')
      planGoal = String(e.goal ?? '')
      planConf = Number(e.confidence ?? 0)
      planCursor = 0
      planSteps = e.stepDetails ?? []
      planBranches = e.branchDetails ?? []
    }
    // 执行器每次决策带上当前计划进度，用它把「计划 → 决策」连起来
    if (e.kind === 'EXEC_DECISION') {
      if (e.planId) planId = String(e.planId)
      if (e.planGoal) planGoal = String(e.planGoal)
      if (e.planCursor !== undefined) planCursor = Number(e.planCursor)
      if (e.planSteps !== undefined && planSteps.length !== Number(e.planSteps)) {
        // 计划步数变了但没收到 PLAN_PUBLISHED（例如重连回放），至少把长度对上
        planSteps = planSteps.slice(0, Number(e.planSteps))
      }
    }
    if (e.kind === 'DUEL_STATUS') {
      const status = String(e.status ?? '')
      // 终态事件带入库策略与决定状态（ask 模式此刻应为 PENDING，前端据此弹询问）
      const archivePatch: Partial<DuelView> = {}
      if (e.archiveState !== undefined) archivePatch.archiveState = e.archiveState
      if (e.archivePolicy !== undefined) archivePatch.archivePolicy = e.archivePolicy
      if (duel) duel = { ...duel, status, ...archivePatch }
      if (TERMINAL_STATUSES.includes(status)) {
        stopSse?.()
        stopSse = null
      }
    }
    events = [e, ...events].slice(0, 80)
  }

  async function start() {
    if (!deckPath.trim() && !deckYdkText.trim()) {
      error = '需要提供卡组：填写卡组路径（或粘贴 .ydk 文本）'
      return
    }
    busy = true
    error = ''
    sseError = ''
    events = []
    execSeqs.clear()
    seenEvents.clear()
    planId = ''
    planThinking = ''
    planSteps = []
    planBranches = []
    planCursor = 0
    submittedBySeq = {}
    try {
      duel = await api.startDuel({
        host, port, name,
        move,
        deckPath: deckPath.trim() || undefined,
        deckYdk: deckYdkText.trim() || undefined,
        archetype: archetype || undefined,
        deckStrategy: deckStrategy.trim() || undefined,
        archivePolicy: archivePolicy as DuelRequest['archivePolicy'],
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
    if (e.kind === 'PLAN_EVENT') return 'PLAN_EVENT'
    if (e.kind === 'DUEL_STATUS') return 'DUEL_STATUS'
    if (e.kind === 'COMBO_RECORDED') return 'COMBO_RECORDED'
    return 'REFLEX'
  }

  function badgeText(e: DuelEvent): string {
    if (e.kind === 'PLAN_EVENT') return PLAN_KIND_LABELS[e.planKind ?? ''] ?? e.planKind ?? '计划'
    if (e.kind === 'DECISION' || e.kind === 'EXEC_DECISION') {
      // 后端会带 levelLabel（中文），历史事件回退到枚举名
      return e.levelLabel ?? e.pathLabel ?? e.level ?? e.pathKind ?? e.kind
    }
    if (e.kind === 'DUEL_STATUS') return '状态'
    if (e.kind === 'COMBO_RECORDED') return '知识入库'
    if (e.kind === 'connected') return '接入'
    return e.kind
  }

  function summarize(e: DuelEvent): string {
    const req = (ev: DuelEvent) => ev.requestLabel ?? ev.requestKind ?? ''
    const act = (ev: DuelEvent) => ev.intentLabel ?? ev.intent ?? ''
    if (e.kind === 'DECISION')
      return `${req(e)} → ${act(e)}${e.explanation ? ' — ' + e.explanation : ''}`
    if (e.kind === 'EXEC_DECISION') {
      const why = e.explanation ? ` — ${e.explanation}` : ''
      return `${req(e)} → ${act(e)}${why}`
    }
    if (e.kind === 'PLAN_EVENT') {
      if (e.planKind === 'PLAN_PUBLISHED')
        return `规划器发布计划 ${e.planId}（${e.steps} 步 / ${e.branches} 分支，置信 ${e.confidence}）：${e.goal ?? ''}`
      if (e.planKind === 'KEEP') return '规划器确认计划仍有效'
      if (e.planKind === 'PARSE_FAILED')
        return `规划器输出解析失败（保持当前计划）${e.reason ? '：' + e.reason : ''}`
      if (e.planKind === 'PLAN_EMPTY') return '规划器返回空内容（按计划维持处理，不降级）'
      if (e.planKind === 'CHECKPOINT_FAILED') return '规划器存档失败（冷却降级）'
      return e.planKind
    }
    if (e.kind === 'DUEL_STATUS') return `对局状态 → ${statusLabel(e.status)}`
    if (e.kind === 'COMBO_RECORDED') return `combo 提取完成：${e.count} 条 → ${e.file ?? ''}`
    if (e.kind === 'connected') return '已接入事件流，等待对局事件…'
    return JSON.stringify(e).slice(0, 120)
  }

  const terminal = $derived(duel && TERMINAL_STATUSES.includes(duel.status))
</script>

<form class="controls" onsubmit={(e) => { e.preventDefault(); start() }}>
  <label>地址 <input bind:value={host} size="12" /></label>
  <label>端口 <input bind:value={port} size="5" /></label>
  <label>名字 <input bind:value={name} size="14" /></label>
  <label>卡组路径 <input bind:value={deckPath} size="34" placeholder="/path/to/卡组.ydk" /></label>
  <label>
    卡组主题
    <select bind:value={archetype}>
      <option value="">不选择</option>
      {#each archetypes as tag}
        <option value={tag}>{tag}</option>
      {/each}
    </select>
  </label>
  <label>
    先后手
    <select bind:value={move}>
      <option value="second">后手</option>
      <option value="first">先手</option>
    </select>
  </label>
  <label
    title="对局结束后的入库方式：结束后问我＝弹出询问；模型判定＝LLM 评估有无沉淀价值，值得才入库；全部入库 / 全部不入库＝不做判断">
    入库策略
    <select bind:value={archivePolicy}>
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
    <span class="count">{deckStrategy.length} 字</span>
    <button type="button" class="link" onclick={() => (strategyOpen = !strategyOpen)}>
      {strategyOpen ? '收起' : '展开'}
    </button>
  </div>
  {#if strategyOpen}
    <textarea
      class="strategybox"
      bind:value={deckStrategy}
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
  <summary class="muted">高级：直接粘贴 .ydk 文本（与卡组路径二选一）</summary>
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

  {#if planId || planSteps.length}
    <PlanPanel
      {planId}
      {planThinking}
      {planGoal}
      {planConf}
      {planCursor}
      {planSteps}
      {planBranches}
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
        {#if e.turn !== undefined}<span class="muted">T{e.turn} </span>{/if}
        {summarize(e)}
        {#if e.kind === 'EXEC_DECISION'}
          <div class="pathrow">
            {#each STAGES as st (st.key)}
              <span class="stage" class:hit={stageOf(e.level) === st.key}>{st.label}</span>
            {/each}
            {#if e.planStep !== undefined}
              <span class="steptag">命中计划步#{e.planStep}</span>
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
    <div class="entry muted">填好上方参数后点「接入并对战」。流程：YGOPro 建房（{host}:{port}）→ 这里接入 → 实时决策流。</div>
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
  .steptag {
    color: #c0a6f0;
    border: 1px dashed #4a3a6a;
    border-radius: 10px;
    padding: 1px 8px;
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
