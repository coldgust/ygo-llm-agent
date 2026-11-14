<script lang="ts">
  import type { AdviceOption } from '../lib/api'

  /** 规划器产出建议的来源 checkpoint → 展示标签。 */
  const CHECKPOINT_LABELS: Record<string, string> = {
    anchor: '回合锚点',
    replan: '重规划',
  }

  let {
    adviceId,
    adviceStale,
    adviceTurn,
    checkpoint = '',
    retired = false,
    strategy,
    analysis,
    resources,
    goal,
    options,
    tactics,
    intel,
    combo,
    adviceConf,
  }: {
    adviceId: string
    adviceStale: boolean
    adviceTurn: number
    checkpoint?: string
    /** 决策器已判决本建议作废（等待重规划发布）：内容置灰展示。 */
    retired?: boolean
    strategy: string
    analysis: string
    resources: string
    goal: string
    options: AdviceOption[]
    tactics: string
    intel: string
    combo: string
    adviceConf: number
  } = $props()
</script>

<div class="prompt advice" class:retired>
  <div class="advicehead">
    <b>规划器建议 {adviceId}</b>
    <span class="muted">
      {#if CHECKPOINT_LABELS[checkpoint]}<span class="ckpt">{CHECKPOINT_LABELS[checkpoint]}</span> · {/if}T{adviceTurn}{#if adviceStale} · 上回合制定，仅供参考{/if}
      {#if adviceConf}· 置信 {adviceConf.toFixed(2)}{/if}
    </span>
  </div>
  {#if retired}
    <div class="retired-banner">
      ⚠ 本建议已被决策器判定为不可实现 / 不合理 / 已偏离局面并作废，等待重规划发布；
      在此期间决策器不带规划自行判断。
    </div>
  {/if}
  {#if strategy}<div class="seg"><span class="k">本局策略</span>{strategy}</div>{/if}
  {#if analysis}<div class="seg"><span class="k">场面</span>{analysis}</div>{/if}
  {#if resources}<div class="seg"><span class="k">资源</span>{resources}</div>{/if}
  {#if goal}<div class="goal"><span class="k">目标</span>{goal}</div>{/if}
  {#if options.length}
    <div class="opts">
      {#each options as o, i (i)}
        <div class="opt" class:picked={o.pick}>
          <div class="opthead">
            <span class="mark">{['①', '②', '③', '④', '⑤'][i] ?? '·'}</span>
            <span class="verdict" class:on={o.pick}>{o.pick ? '✓ 采用' : '淘汰'}</span>
          </div>
          <div class="optline">{o.line.join(' → ')}</div>
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
  {/if}
  {#if tactics}<div class="seg"><span class="k">战术</span>{tactics}</div>{/if}
  {#if intel}<div class="seg intel"><span class="k">情报要点</span>{intel}</div>{/if}
  {#if combo}<div class="seg combo"><span class="k">combo</span>{combo}</div>{/if}
</div>

<style>
  .advice {
    border: 1px solid #2f4470;
    background: #111725;
  }
  .advice.retired {
    border-color: #6a5a2f;
    opacity: 0.62;
  }
  .retired-banner {
    margin: 0 0 8px;
    padding: 6px 10px;
    border: 1px dashed #8a7a3f;
    border-radius: 6px;
    background: #1c1a10;
    color: #d9c07a;
    font-size: 12px;
    line-height: 1.5;
  }
  .ckpt {
    color: #c0a6f0;
    border: 1px solid #4a3a6a;
    border-radius: 8px;
    padding: 0 6px;
    font-size: 11px;
  }
  .advicehead {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 12px;
    margin-bottom: 6px;
  }
  .advicehead b {
    color: #7aa2f7;
  }
  .advice .k {
    color: #6b7383;
    font-size: 12px;
    margin-right: 6px;
  }
  .advice .goal {
    font-size: 12.5px;
    color: #c7ccd8;
    margin-bottom: 4px;
  }
  .advice .seg {
    color: #9aa4b8;
    font-size: 12px;
    line-height: 1.6;
    margin: 3px 0;
    white-space: pre-wrap;
  }
  .advice .seg.combo { color: #8fb3a8; }
  .advice .seg.intel { color: #d9b46b; }

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
</style>
