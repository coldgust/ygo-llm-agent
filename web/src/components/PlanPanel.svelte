<script lang="ts">
  import { parsePlanThinking } from '../lib/planView'
  import type { StepDetail, BranchDetail } from '../lib/api'

  let {
    planId,
    planThinking,
    planGoal,
    planConf,
    planCursor,
    planSteps,
    planBranches,
  }: {
    planId: string
    planThinking: string
    planGoal: string
    planConf: number
    planCursor: number
    planSteps: StepDetail[]
    planBranches: BranchDetail[]
  } = $props()

  let planView = $derived(parsePlanThinking(planThinking))
</script>

<div class="prompt plan">
  <div class="planhead">
    <b>规划器计划 {planId}</b>
    <span class="muted">
      {#if planSteps.length}
        进度 {Math.min(planCursor, planSteps.length)}/{planSteps.length}
      {:else}
        顾问模式 · 不提供固定路径
      {/if}
      {#if planConf}· 置信 {planConf.toFixed(2)}{/if}
    </span>
  </div>
  {#if planGoal}<div class="goal"><span class="k">目标</span>{planGoal}</div>{/if}
  {#if planView.analysis}<div class="seg"><span class="k">场面</span>{planView.analysis}</div>{/if}
  {#if planView.options.length}
    <div class="opts">
      {#each planView.options as o (o.mark)}
        <div class="opt" class:picked={o.picked}>
          <div class="opthead">
            <span class="mark">{o.mark}</span>
            <span class="verdict" class:on={o.picked}>{o.picked ? '✓ 采用' : '淘汰'}</span>
          </div>
          <div class="optline">{o.line}</div>
          {#if o.gain || o.risk}
            <div class="optmeta">
              {#if o.gain}<span class="gain">收益 · {o.gain}</span>{/if}
              {#if o.risk}<span class="risk">风险 · {o.risk}</span>{/if}
            </div>
          {/if}
          {#if o.why}<div class="optwhy">{o.why}</div>{/if}
        </div>
      {/each}
    </div>
  {:else if planThinking && !planView.analysis && !planView.tactics && !planView.combo}
    <div class="thinking">{planThinking}</div>
  {/if}
  {#if planView.tactics}<div class="seg"><span class="k">战术</span>{planView.tactics}</div>{/if}
  {#if planView.combo}<div class="seg combo"><span class="k">combo</span>{planView.combo}</div>{/if}
  {#if planSteps.length}
    <ol class="plansteps">
      {#each planSteps as s, i (i)}
        <li class:done={i < planCursor} class:current={i === planCursor}>
          <span class="idx">{i + 1}</span>
          <span class="muted">[{s.when}]</span>
          <b>{s.verb}</b>
          {#if s.passcode}<code>{s.passcode}</code>{/if}
          {#if s.zone}<span class="zone">→ {s.zone}</span>{/if}
          <span class="why">— {s.why}</span>
        </li>
      {/each}
    </ol>
    {#if planCursor >= planSteps.length && planSteps.length > 0}
      <div class="muted">计划已走完 → 后续决策改走 ④决策器判断 / ⑤兜底</div>
    {/if}
  {/if}
  {#if planBranches.length}
    <details class="branches">
      <summary class="muted">分支 {planBranches.length} 条（应对对手不同打法）</summary>
      <ul>
        {#each planBranches as b, i (i)}
          <li>
            <b>{b.name}</b>
            <span class="muted">{b.assumes}</span>
            <span class="arrow">→</span>{b.steps.join(' → ')}
          </li>
        {/each}
      </ul>
    </details>
  {/if}
</div>

<style>
  .plan {
    border: 1px solid #2f4470;
    background: #111725;
  }
  .planhead {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 12px;
    margin-bottom: 6px;
  }
  .planhead b {
    color: #7aa2f7;
  }
  .plan .k {
    color: #6b7383;
    font-size: 12px;
    margin-right: 6px;
  }
  .plan .thinking {
    color: #9aa4b8;
    font-size: 12px;
    margin: 4px 0 6px;
    line-height: 1.5;
  }
  .plan .goal {
    font-size: 12.5px;
    color: #c7ccd8;
    margin-bottom: 4px;
  }
  .plan .seg {
    color: #9aa4b8;
    font-size: 12px;
    line-height: 1.6;
    margin: 3px 0;
    white-space: pre-wrap;
  }
  .plan .seg.combo { color: #8fb3a8; }

  .opts {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 6px 0;
  }
  .opt {
    border: 1px solid #232a38;
    border-left: 3px solid #2b3345;
    border-radius: 4px;
    padding: 6px 8px;
    background: #0e1420;
    font-size: 12px;
    color: #a8b0c2;
    opacity: 0.78;
  }
  .opt.picked {
    border-color: #2f6d4f;
    border-left-color: #3fbf7f;
    background: #0f1a16;
    color: #d5e6dd;
    opacity: 1;
  }
  .opthead {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 3px;
  }
  .opt .mark {
    color: #6b7383;
    font-weight: 600;
  }
  .opt.picked .mark { color: #3fbf7f; }
  .opt .verdict {
    font-size: 11px;
    padding: 0 5px;
    border-radius: 3px;
    background: #1c2330;
    color: #6b7383;
  }
  .opt .verdict.on {
    background: #17352a;
    color: #4fd68f;
  }
  .optline { line-height: 1.55; }
  .optmeta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 3px;
    font-size: 11.5px;
  }
  .optmeta .gain { color: #7aa2f7; }
  .optmeta .risk { color: #d99a5b; }
  .optwhy {
    margin-top: 3px;
    font-size: 11.5px;
    color: #8b93a6;
  }
  .plansteps {
    list-style: none;
    margin: 4px 0 0;
    padding: 0;
    font-size: 12.5px;
  }
  .plansteps li {
    display: flex;
    gap: 6px;
    align-items: baseline;
    padding: 3px 6px;
    border-left: 2px solid #232a38;
    color: #c7ccd8;
  }
  .plansteps li .idx {
    color: #4b5468;
    min-width: 14px;
  }
  .plansteps li code {
    color: #c0a6f0;
    background: #1a1f2b;
    padding: 0 4px;
    border-radius: 3px;
  }
  .plansteps li .why {
    color: #8b94a7;
  }
  .plansteps li .zone {
    color: #6fd0b8;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.92em;
  }
  .plansteps li.done {
    opacity: 0.45;
    border-left-color: #2f4470;
    text-decoration: line-through;
    text-decoration-color: #3a4356;
  }
  .plansteps li.current {
    border-left-color: #7aa2f7;
    background: #16203a;
    color: #e6ecff;
  }
  .plansteps li.current .idx {
    color: #7aa2f7;
  }
  .branches {
    margin-top: 8px;
    font-size: 12px;
  }
  .branches ul {
    margin: 6px 0 0;
    padding-left: 18px;
    color: #c7ccd8;
  }
  .branches li {
    margin-bottom: 3px;
    line-height: 1.5;
  }
  .branches .arrow {
    color: #4b5468;
    margin: 0 4px;
  }
</style>
