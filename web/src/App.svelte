<script lang="ts">
  import DuelView from './pages/DuelView.svelte'
  import ComboView from './pages/ComboView.svelte'

  type Tab = 'duel' | 'combo'
  let tab: Tab = $state('duel')

  const tabs: { id: Tab; label: string; desc: string }[] = [
    { id: 'duel', label: '对战', desc: '接入 YGOPro 房间，实时观看规划器与执行器的每一步决策' },
    { id: 'combo', label: 'Combo 录入', desc: '你先手打一套连招，AI 回合自动认输；逐步操作与 LLM 摘要存入 PostgreSQL' },
  ]
</script>

<main>
  <header>
    <h1>YGO Agent</h1>
    <nav>
      {#each tabs as t (t.id)}
        <button class:active={tab === t.id} onclick={() => (tab = t.id)}>{t.label}</button>
      {/each}
    </nav>
  </header>
  <p class="desc">{tabs.find((t) => t.id === tab)?.desc}</p>

  {#if tab === 'duel'}
    <DuelView />
  {:else}
    <ComboView />
  {/if}
</main>

<style>
  :global(body) {
    margin: 0;
    background: #0f1115;
    color: #d7dae0;
    font-family: 'SF Mono', 'JetBrains Mono', Menlo, 'PingFang SC', monospace;
    font-size: 14px;
  }
  main {
    max-width: 980px;
    margin: 0 auto;
    padding: 20px;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 10px;
  }
  h1 {
    font-size: 18px;
    margin: 0;
  }
  .badge {
    font-size: 11px;
    color: #7aa2f7;
    border: 1px solid #2a3245;
    border-radius: 4px;
    padding: 2px 6px;
    margin-left: 6px;
  }
  nav {
    display: flex;
    gap: 6px;
  }
  nav button {
    background: #161a22;
    color: #9aa3b2;
    border: 1px solid #2a3245;
    border-radius: 6px;
    padding: 6px 14px;
    cursor: pointer;
    font-family: inherit;
  }
  nav button.active {
    color: #e8eaf0;
    border-color: #7aa2f7;
    background: #1b2233;
  }
  .desc {
    color: #6b7383;
    font-size: 12px;
    margin: 8px 0 16px;
  }
</style>
