<script lang="ts">
	import type { Material } from '$lib/types';
	import { TYPE_LABELS, materialType, typesIn, type MaterialType } from './materialTypes';

	// Разделение материалов по типу (0.9.3): «Все / Конспекты / PDF / Презентации…» — только если
	// типов в списке несколько. onshown — отфильтрованный список наружу.
	let { list, onshown }: { list: Material[]; onshown: (shown: Material[]) => void } = $props();

	let type = $state<MaterialType | 'all'>('all');
	const types = $derived(typesIn(list));
	$effect(() => {
		if (type !== 'all' && !types.some((t) => t.type === type)) type = 'all';
		onshown(type === 'all' ? list : list.filter((m) => materialType(m) === type));
	});
</script>

{#if types.length > 1}
	<div class="types" role="radiogroup" aria-label="Тип материалов">
		<button
			type="button"
			role="radio"
			aria-checked={type === 'all'}
			class:on={type === 'all'}
			onclick={() => (type = 'all')}>Все <span class="num">{list.length}</span></button
		>
		{#each types as t (t.type)}
			<button
				type="button"
				role="radio"
				aria-checked={type === t.type}
				class:on={type === t.type}
				onclick={() => (type = t.type)}
				>{TYPE_LABELS[t.type]} <span class="num">{t.count}</span></button
			>
		{/each}
	</div>
{/if}

<style>
	.types {
		display: flex;
		gap: 6px;
		margin-bottom: var(--s3);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.types button {
		flex: none;
		padding: 6px 12px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font: inherit;
		font-size: 14px;
		font-weight: 600;
		cursor: pointer;
	}
	.types button.on {
		border-color: transparent;
		background: var(--text);
		color: var(--bg);
	}
	.num {
		opacity: 0.6;
		margin-left: 2px;
	}
</style>
