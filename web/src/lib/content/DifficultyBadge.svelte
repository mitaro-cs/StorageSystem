<script lang="ts">
	import { difficultyOf } from './difficulty';

	// Три полоски, как у сигнала: сколько закрашено — такая и сложность.
	let { value, compact = false }: { value: number | null | undefined; compact?: boolean } =
		$props();
	const d = $derived(difficultyOf(value));
</script>

{#if d}
	<span class="diff {d.tone}" class:compact title="Сложность: {d.label.toLowerCase()}">
		<span class="bars" aria-hidden="true">
			{#each [1, 2, 3] as b (b)}<span class="bar" class:on={b <= d.value} style:--h="{3 + b * 3}px"
				></span>{/each}
		</span>
		{#if !compact}<span class="t">{d.label}</span>{:else}<span class="sr-only">{d.label}</span>{/if}
	</span>
{/if}

<style>
	.diff {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 24px;
		padding: 0 9px 0 8px;
		border-radius: var(--r-full);
		font-size: 12.5px;
		font-weight: 600;
		white-space: nowrap;
		color: var(--tone);
		background: color-mix(in srgb, var(--tone) 12%, transparent);
	}
	.compact {
		padding: 0 6px;
		height: 20px;
	}
	.ok {
		--tone: var(--ok);
	}
	.amber {
		--tone: var(--amber);
	}
	.danger {
		--tone: var(--danger);
	}
	.bars {
		display: inline-flex;
		align-items: flex-end;
		gap: 2px;
		height: 12px;
	}
	.bar {
		width: 3px;
		height: var(--h);
		border-radius: 2px;
		background: color-mix(in srgb, var(--tone) 30%, transparent);
	}
	.bar.on {
		background: var(--tone);
	}
</style>
