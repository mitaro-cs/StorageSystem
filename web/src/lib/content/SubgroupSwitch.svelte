<script lang="ts">
	import { goto } from '$app/navigation';
	import { Users } from '@lucide/svelte';
	import { put } from '$lib/api';
	import { loadSubjects, subjects as all } from '$lib/data.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import { choices } from './subgroups';

	// «Моя подгруппа» на странице предмета (0.7): у предметов «… №1», «… №2» — выбор своей. Выбранная
	// — «мой предмет», остальные скрываются у этого человека («не мой предмет»).
	let { subject }: { subject: Subject } = $props();

	const set = $derived(
		choices(all.list).find((c) => c.options.some((o) => o.subject.id === subject.id))
	);
	let busy = $state(false);

	async function choose(id: number) {
		if (!set || busy) return;
		busy = true;
		try {
			for (const o of set.options)
				await put(`/api/subjects/${o.subject.id}/mine`, { value: o.subject.id === id });
			await loadSubjects();
			if (id !== subject.id) goto(`/subjects/${id}`, { replaceState: true });
			else location.reload();
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}
</script>

{#if set}
	<div class="sub card" role="group" aria-label="Моя подгруппа">
		<span class="lbl"><Users size={16} /> Моя подгруппа</span>
		<div class="opts">
			{#each set.options as o (o.subject.id)}
				{@const mine = o.subject.mine !== false}
				<button
					class:on={o.subject.id === subject.id && mine}
					class:hidden={!mine}
					disabled={busy}
					aria-pressed={o.subject.id === subject.id && mine}
					onclick={() => choose(o.subject.id)}>{o.label}</button
				>
			{/each}
		</div>
	</div>
{/if}

<style>
	.sub {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: var(--s2) var(--s3);
		padding: 10px var(--s4);
		margin-bottom: var(--s3);
	}
	.lbl {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-weight: 600;
		color: var(--text-2);
	}
	.opts {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	button {
		height: 34px;
		padding: 0 14px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	button.hidden {
		color: var(--text-3);
	}
	button.on {
		border-color: transparent;
		background: var(--accent);
		color: var(--accent-text);
	}
</style>
