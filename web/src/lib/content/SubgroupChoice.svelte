<script lang="ts">
	import { Split } from '@lucide/svelte';
	import { put } from '$lib/api';
	import { loadSubjects, subjects } from '$lib/data.svelte';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { typo } from '$lib/typo';
	import { choices, dismiss, dismissed, open, type Choice } from './subgroups';

	// «Английский идёт по подгруппам — ваша?»: выбрали — задания, новости и уведомления другой
	// подгруппы больше не приходят. Передумали — «Не мой предмет» на странице предмета.
	let { onchange }: { onchange?: () => void } = $props();

	let skip = $state(dismissed());
	let busy = $state<string | null>(null);
	const list = $derived(
		choices(
			subjects.list.filter(
				(s) => session.groupId === null || s.groups.some((g) => g.id === session.groupId)
			)
		).filter((c) => open(c, skip))
	);

	async function pick(c: Choice, id: number) {
		busy = c.key;
		try {
			for (const o of c.options)
				await put(`/api/subjects/${o.subject.id}/mine`, { value: o.subject.id === id });
			await loadSubjects();
			toast('Готово: другая подгруппа больше не будет мешать', 'ok');
			onchange?.();
		} catch (e) {
			toastError(e);
		} finally {
			busy = null;
		}
	}

	function all(c: Choice) {
		dismiss(c.key);
		skip = dismissed();
	}
</script>

{#each list as c (c.key)}
	<section class="card choice" aria-label="Подгруппа: {c.title}">
		<div class="top">
			<span class="ic" aria-hidden="true"><Split size={20} /></span>
			<div>
				<h3>{typo(`${c.title} — по подгруппам. Какая ваша?`)}</h3>
				<p class="muted small">
					{typo('Задания, новости и уведомления другой подгруппы приходить не будут.')}
				</p>
			</div>
		</div>
		<div class="options">
			{#each c.options as o (o.subject.id)}
				<button
					class="opt"
					style:--c={o.subject.color}
					disabled={busy === c.key}
					onclick={() => pick(c, o.subject.id)}
				>
					<i aria-hidden="true"></i>
					{o.label}
				</button>
			{/each}
		</div>
		<button class="linklike small" onclick={() => all(c)}>Хожу на все</button>
	</section>
{/each}

<style>
	.choice {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		margin-bottom: var(--s4);
		padding: var(--s4) var(--s5);
		box-shadow:
			inset 0 0 0 1.5px color-mix(in srgb, var(--accent) 35%, transparent),
			var(--shadow-1);
	}
	.top {
		display: flex;
		gap: 12px;
		align-items: flex-start;
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		background: var(--accent-soft);
		color: var(--accent);
	}
	h3 {
		font-size: 17px;
		line-height: 1.3;
	}
	.top p {
		margin-top: 2px;
	}
	.options {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.opt {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		min-height: 44px;
		padding: 0 18px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
		transition:
			background-color var(--dur) var(--ease),
			border-color var(--dur) var(--ease);
	}
	.opt:hover {
		border-color: var(--c);
		background: color-mix(in srgb, var(--c) 10%, var(--surface));
	}
	.opt i {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--c);
	}
	.opt:disabled {
		opacity: 0.6;
	}
	.linklike {
		align-self: flex-start;
	}
</style>
