<script lang="ts">
  import { onMount } from 'svelte'
  import { api, type DuelEvent } from '../lib/api'

  let host = $state('127.0.0.1')
  let port = $state(7911)
  let name = $state('combo-record')
  let deckPath = $state('')
  let archetype = $state('')
  let archetypes = $state<string[]>([])

  let duelId = $state('')
  let events: DuelEvent[] = $state([])
  // running 只控制「停止」按钮与状态语义，**不禁用开始按钮**：
  // 按钮被独占禁用是"永远卡死"的根源——任何一条解锁路径失效（SSE 断、事件丢、bundle 陈旧），
  // 用户就被锁死且毫无办法。重复点击交给服务端并发上限（409）明确报错，本局结果不受影响。
  let running = $state(false)
  let submitting = $state(false) // 仅 startDuel POST 在途的秒级窗口
  let recorded: { summary: string; steps: string[] } | null = $state(null)
  let recordedMeta = $state({ count: 0, archetype: '', recordId: 0, summarySource: '' })
  let error = $state('')
  let stopSse: (() => void) | null = null

  // 已有主题来自种子编译目录 ∪ PG 实战录入；输入框允许直接起新主题。
  // 上次录入的对局从 localStorage 找回：刷新页面后重订阅（历史回放）+ 轮询，结果照样能显示。
  onMount(() => {
    api.listArchetypes().then((list) => (archetypes = list)).catch(() => {})
    const saved = localStorage.getItem('combo-duel-id')
    if (saved) {
      duelId = saved
      running = true
      stopSse = api.subscribe(saved, onEvent, (message, fatal) => {
        error = message
        if (fatal) finish()
      })
      startPolling()
    }
  })

  /** 终态：无论录入成功与否，都解除按钮禁用，让用户能重开一局。 */
  const TERMINAL = ['FINISHED', 'STOPPED', 'FAILED']

  let pollTimer: ReturnType<typeof setInterval> | null = null

  function stopPoll() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function finish(message?: string) {
    running = false
    if (message && !error) error = message
    stopSse?.()
    stopSse = null
    stopPoll()
  }

  /** 渲染一条录入结果（SSE 与 HTTP 轮询共用）。 */
  function showRecorded(e: { count: number; archetype?: string; recordId?: number; summary?: string | null; summarySource?: string; stepPreviews?: string[] }) {
    recorded = { summary: String(e.summary ?? ''), steps: e.stepPreviews ?? [] }
    recordedMeta = {
      count: Number(e.count ?? 0),
      archetype: String(e.archetype ?? ''),
      recordId: Number(e.recordId ?? 0),
      summarySource: String(e.summarySource ?? ''),
    }
    error = '' // 清掉可能残留的"自动重连"瞬时提示
    finish()
  }

  function onEvent(e: DuelEvent) {
    if (e.kind === 'DECISION') return // 与 EXEC_DECISION 重复
    events = [e, ...events].slice(0, 60)
    if (e.kind === 'COMBO_RECORDED') {
      if (Number(e.count) > 0) {
        showRecorded(e)
      } else {
        error = String(e.error ?? e.reason ?? '没有记录到操作')
        finish()
      }
      return
    }
    if (e.kind === 'DUEL_STATUS' && TERMINAL.includes(String(e.status)) && !recorded) {
      // 正常成功的场合 COMBO_RECORDED 先到、SSE 已关；走到这里说明没录成（失败/手动停止）。
      // 但 SSE 可能丢过事件：先查一次库，真的没有才报"未完成录入"。
      fetchRecordFromServer()
    }
  }

  /** HTTP 兜底：SSE 的 COMBO_RECORDED 没收到时，直接查 PG 里的录入结果。 */
  async function fetchRecordFromServer() {
    if (!duelId || recorded) {
      finish()
      return
    }
    try {
      const rec = await api.getComboRecord(duelId)
      showRecorded(rec)
    } catch {
      finish('对局已结束，未查询到录入记录')
    }
  }

  /** 轮询兜底：SSE 静默断掉/事件丢失时，靠状态轮询把页面带回到终态。 */
  async function pollOnce() {
    if (!duelId) return
    try {
      const d = await api.getDuel(duelId)
      if (TERMINAL.includes(d.status) && running) {
        stopPoll()
        if (!recorded) await fetchRecordFromServer()
        running = false
        stopSse?.()
        stopSse = null
      }
    } catch {
      // 404 = 服务端重启过、对局已不在；其余（网络抖动）下轮再试
      stopPoll()
      if (running && !recorded) {
        error = '对局状态查询失败（服务端可能已重启），请重新开始录入'
        running = false
        stopSse?.()
        stopSse = null
      }
    }
  }

  function startPolling() {
    stopPoll()
    pollTimer = setInterval(pollOnce, 4000)
  }

  async function start() {
    if (submitting) return
    if (!deckPath.trim()) {
      error = '需要提供 AI 卡组路径'
      return
    }
    if (!archetype.trim()) {
      error = '需要填写卡组主题（录入的连招按它归档，也是之后对局上下文注入与 find_combos 检索的键）'
      return
    }
    error = ''
    submitting = true
    try {
      const duel = await api.startDuel({
        host, port, name,
        move: 'second', // 先手让给用户
        deckPath: deckPath || undefined,
        archetype: archetype.trim() || undefined,
        comboRecord: true,
      })
      // 成功才切换到新一局：409（已有对局进行中）等失败不影响已显示的上一局结果
      recorded = null
      events = []
      running = true
      duelId = duel.id
      localStorage.setItem('combo-duel-id', duel.id) // 刷新页面后也能找回这局的结果
      stopSse = api.subscribe(duel.id, onEvent, (message, fatal) => {
        error = message
        if (fatal) finish() // 不会再重连（服务端不可达/对局不存在）：立即解锁
      })
      startPolling()
    } catch (e: any) {
      error = String(e.message ?? e) // 典型：已有对局进行中（最多 1 局）——先停止再开
    } finally {
      submitting = false
    }
  }

  async function stop() {
    if (!duelId) return
    try {
      await api.stopDuel(duelId) // 停止后的终态 DUEL_STATUS 负责解锁
    } catch (e: any) {
      // 停止请求本身失败（如服务端不可达）：别把用户锁死在"录入中"
      error = String(e.message ?? e)
      running = false
      stopSse?.()
      stopSse = null
      stopPoll()
    }
  }

  /** 对局状态、规划器子类型的展示词（原始枚举值不上屏）。 */
  const STATUS_LABELS: Record<string, string> = {
    STARTING: '准备中', RUNNING: '进行中', STOPPING: '停止中',
    STOPPED: '已停止', FINISHED: '已结束', FAILED: '异常结束',
  }

  function summarize(e: DuelEvent): string {
    if (e.kind === 'EXEC_DECISION')
      return `${e.requestLabel ?? e.requestKind} → ${e.intentLabel ?? e.intent ?? ''}`
    if (e.kind === 'PLAN_EVENT' && e.planKind === 'PLAN_PUBLISHED') return `计划发布（${e.steps} 步）`
    if (e.kind === 'DUEL_STATUS') return `状态 → ${(e.status && STATUS_LABELS[e.status]) || e.status || ''}`
    return e.error ?? e.reason ?? ''
  }
</script>

<form class="controls" onsubmit={(e) => { e.preventDefault(); start() }}>
  <label>地址 <input bind:value={host} size="12" /></label>
  <label>端口 <input bind:value={port} size="5" /></label>
  <label>名字 <input bind:value={name} size="14" /></label>
  <label>AI 卡组路径 <input bind:value={deckPath} size="30" placeholder="AI 用的 .ydk（如蓝眼）" /></label>
  <label>
    卡组主题
    <input
      list="archetype-options"
      bind:value={archetype}
      size="14"
      placeholder="必填：选已有或输入新主题"
    />
    <datalist id="archetype-options">
      {#each archetypes as tag (tag)}
        <option value={tag}></option>
      {/each}
    </datalist>
  </label>
  <button class="go" type="submit" disabled={submitting}>开始录入</button>
  {#if running}
    <button type="button" onclick={stop}>停止</button>
  {/if}
</form>

<p class="muted" style="margin-top:0">
  流程：YGOPro 建房 → 这里开始录入（agent 自动后手）→ 你先手打你的 combo →
  轮到 AI 回合时自动认输结束，逐步操作存入 PostgreSQL，并由 LLM 生成摘要。
  卡组主题必填：选已有或输入新名字建新主题——它是录入归档的键，之后对战时按它注入上下文。
</p>

{#if error}<p class="error">{error}</p>{/if}

{#if recorded}
  <div class="prompt">
    <div>
      ✅ 录入完成（{recordedMeta.count} 步 → PG combo_record#{recordedMeta.recordId}，主题
      {recordedMeta.archetype}）
    </div>
    {#if recorded.summary}
      <div class="entry">{recorded.summary}</div>
    {:else}
      <div class="entry muted">（LLM 摘要未生成，仅存了操作步骤）</div>
    {/if}
    {#each recorded.steps as step (step)}
      <div class="entry muted">{step}</div>
    {/each}
  </div>
{/if}

<div class="feed">
  {#each events as e, i (i)}
    <div class="entry">
      <span class="badge PLAN_EVENT">{e.kind}{e.planKind ? '·' + e.planKind : ''}</span>
      {#if e.turn !== undefined}<span class="muted">T{e.turn} </span>{/if}
      {summarize(e)}
    </div>
  {:else}
    <div class="entry muted">等待事件…</div>
  {/each}
</div>
