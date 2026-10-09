<script lang="ts">
	import { canRetake, kindOf, retakeLabel } from './kinds';
	import KindIcon from './KindIcon.svelte';

	// Домашнее – обычный случай, метка не нужна; остальные типы видно сразу.
	let {
		kind,
		compact = false,
		retake = false
	}: { kind: string | null | undefined; compact?: boolean; retake?: boolean } = $props();
	const k = $derived(kindOf(kind));
	const label = $derived(retake && canRetake(k.value) ? retakeLabel(k.value) : k.label);
</script>

{#if k.value !== 'homework'}
	<span class="kind {k.value}" class:compact class:retake title={label}>
		<KindIcon kind={k.value} size={compact ? 12 : 13} />
		<span class="t">{label}</span>
	</span>
{/if}

<style>
	.kind {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		height: 24px;
		padding: 0 9px 0 8px;
		border-radius: var(--r-full);
		font-size: 12.5px;
		font-weight: 600;
		white-space: nowrap;
		color: var(--text-2);
		background: var(--surface-2);
	}
	.compact {
		height: 20px;
		padding: 0 7px 0 6px;
		font-size: 12px;
	}
	.test {
		color: var(--amber);
		background: var(--amber-soft);
	}
	.credit {
		color: var(--text);
		background: var(--surface-3);
	}
	.exam {
		color: var(--inverse-text);
		background: var(--inverse);
	}
	/* Пересдача – пунктирная рамка поверх цвета типа: видно, что это не первая попытка. */
	.retake {
		box-shadow: inset 0 0 0 1.5px currentColor;
		background-image: none;
	}
</style>
