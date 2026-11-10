export interface DuelRequest {
  host: string
  port: number
  name?: string
  move?: 'first' | 'second'
  deckPath?: string
  deckYdk?: string
  archetype?: string
  comboRecord?: boolean
  /** 卡组打法思路（用户手写）：注入 LLM 上下文作为重点参考 */
  deckStrategy?: string
  /** 对局库入库策略：ask 结束后询问 / auto 模型判定价值 / always 全部入库 / never 全部不入库 */
  archivePolicy?: 'ask' | 'auto' | 'always' | 'never'
}

export interface DuelView {
  id: string
  status: string
  host: string
  port: number
  name: string
  move: string
  deckSource: string
  archetype: string | null
  startedAt: string | null
  finishedAt: string | null
  answered: number | null
  fallbacks: number | null
  winner: string | null
  reason: string | null
  error: string | null
  tracePath: string
  duelLogPath: string
  archivePolicy?: string | null
  /** ask 模式下的入库决定状态：PENDING 等用户决定 → ACCEPTED / DECLINED */
  archiveState?: string | null
}

export interface StepDetail {
  index?: number
  when: string
  verb: string
  passcode: number
  /** 计划指定的放置位置编码（MMZ-3/STZ-2/EMZ-1…）；空 = 未指定，届时决策器判断 */
  zone?: string
  why: string
}

export interface BranchDetail {
  name: string
  assumes: string
  steps: string[]
}

export interface DuelEvent {
  // 服务端用 SSE 的 event name 区分类型，必须逐个注册监听（见 subscribe）；
  // 新增事件类型忘了加进来，前端就"永远收不到"而且不报错
  kind: string // DECISION / EXEC_DECISION / SUBMITTED / PLAN_EVENT / DUEL_STATUS / COMBO_RECORDED / connected
  seq?: number
  turn?: number
  phase?: string
  requestKind?: string
  pathKind?: string
  level?: string
  levelLabel?: string
  requestLabel?: string
  pathLabel?: string
  intent?: string
  /** 意图的中文短标签（后端附带；历史事件回退到 intent 枚举名） */
  intentLabel?: string
  /** 最终提交的操作摘要（SUBMITTED 事件） */
  submitted?: string
  fallback?: boolean
  confidence?: number
  explanation?: string
  missReasons?: string
  planKind?: string
  planId?: string
  planCursor?: number
  planSteps?: number
  planGoal?: string
  planStep?: number
  steps?: number
  branches?: number
  thinking?: string
  goal?: string
  stepDetails?: StepDetail[]
  branchDetails?: BranchDetail[]
  status?: string
  winner?: string | null
  reason?: string | null
  archivePolicy?: string | null
  archiveState?: string | null
  answered?: number | null
  fallbacks?: number | null
  tracePath?: string
  duelLogPath?: string
  count?: number
  /** COMBO_RECORDED：录入落库的记录 id（PG combo_record.id） */
  recordId?: number
  archetype?: string
  /** COMBO_RECORDED：逐步操作的自然语言描述（前 8 条预览，全量在库里） */
  stepPreviews?: string[]
  /** COMBO_RECORDED：LLM 摘要（summarySource=NONE 时为空） */
  summary?: string
  summarySource?: string
  error?: string
  duelId?: string
  [k: string]: unknown
}

export interface ComboRecordPayload {
  duelId: string
  recordId: number
  archetype: string
  count: number
  stepPreviews: string[]
  summary: string | null
  summarySource: string
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    throw new Error(`${res.status}: ${text.slice(0, 300)}`)
  }
  return res.json() as Promise<T>
}

export const api = {
  /** combo 库里有哪主题（前端"卡组主题"下拉的数据源）。 */
  listArchetypes(): Promise<string[]> {
    return fetch('/api/duels/archetypes').then((r) => json<string[]>(r))
  },

  startDuel(req: DuelRequest): Promise<DuelView> {
    return fetch('/api/duels', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    }).then((r) => json<DuelView>(r))
  },

  getDuel(id: string): Promise<DuelView> {
    return fetch(`/api/duels/${id}`).then((r) => json<DuelView>(r))
  },

  /** 某局对局的 combo 录入结果（SSE 丢失时的 HTTP 兜底）；无记录 404。 */
  getComboRecord(id: string): Promise<ComboRecordPayload> {
    return fetch(`/api/duels/${id}/combo`).then((r) => json<ComboRecordPayload>(r))
  },

  stopDuel(id: string): Promise<DuelView> {
    return fetch(`/api/duels/${id}/stop`, { method: 'POST' }).then((r) => json<DuelView>(r))
  },

  /** 「结束后询问」模式下用户决定本局是否写入对局库（store=true 写入）。 */
  archiveDuel(id: string, store: boolean): Promise<DuelView> {
    return fetch(`/api/duels/${id}/archive`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ store }),
    }).then((r) => json<DuelView>(r))
  },

  /**
   * 订阅对局事件流；返回关闭函数。
   *
   * EventSource 自带重连：服务端 complete() 后浏览器会在约 3s 后重连并再次回放历史，
   * 调用方需自行按事件去重（见 DuelView.dedupeKey）。
   * onError 的 fatal=true 表示不会再重连（对局不存在/服务端不可达），
   * 调用方应当结束等待；fatal=false 只是瞬时断线，浏览器在自动重连。
   */
  subscribe(
    duelId: string,
    onEvent: (e: DuelEvent) => void,
    onError?: (message: string, fatal: boolean) => void,
  ): () => void {
    const source = new EventSource(`/api/duels/${duelId}/events`)
    const names = ['DECISION', 'EXEC_DECISION', 'SUBMITTED', 'PLAN_EVENT',
      'DUEL_STATUS', 'COMBO_RECORDED', 'connected']
    const handlers = names.map((name) => {
      const handler = (ev: MessageEvent) => {
        try {
          onEvent({ ...JSON.parse(ev.data), kind: name === 'connected' ? 'connected' : name })
        } catch {
          onEvent({ kind: name, error: ev.data })
        }
      }
      source.addEventListener(name, handler as EventListener)
      return { name, handler }
    })

    let closed = false
    const close = () => {
      if (closed) return
      closed = true
      handlers.forEach(({ name, handler }) => source.removeEventListener(name, handler as EventListener))
      source.close()
    }

    source.onerror = () => {
      if (closed) return
      if (source.readyState === EventSource.CLOSED) {
        // 不会再重连：对局不存在 / 服务端已终止 / 网络不可达
        onError?.('事件流已断开，不再重连（对局可能已结束或服务端不可达）', true)
        close()
      } else {
        onError?.('事件流连接异常，浏览器正在自动重连…', false)
      }
    }

    return close
  },
}
