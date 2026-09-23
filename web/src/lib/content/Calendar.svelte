<script lang="ts">
	import { ChevronLeft, ChevronRight } from '@lucide/svelte';
	import { get, qs } from '$lib/api';
	import { session } from '$lib/session.svelte';
	import { startOfDay } from '$lib/format';
	import { fade } from '$lib/motion';
	import type { Homework } from '$lib/types';

	let { subjectId = null }: { subjectId?: number | null } = $props();

	const initial = new Date();
	let year = $state(initial.getFullYear());
	let month = $state(initial.getMonth());
	let items = $state<Homework[]>([]);
	let selected = $state<number>(startOfDay(Date.now()));

	const monthName = $derived(
		new Date(year, month, 1).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })
	);
	const cells = $derived.by(() => {
		const offset = (new Date(year, month, 1).getDay() + 6) % 7; // понедельник — первый
		return Array.from({ length: 42 }, (_, i) => new Date(year, month, 1 - offset + i));
	});
	const byDay = $derived.by(() => {
		const m: Record<number, Homework[]> = {};
		for (const h of items) {
			const k = startOfDay(h.dueAt);
			m[k] = [...(m[k] ?? []), h];
		}
		return m;
	});
	const today = startOfDay(Date.now());

	$effect(() => {
		const from = cells[0].getTime();
		const to = cells[41].getTime() + 24 * 3600 * 1000;
		get<Homework[]>(
			`/api/homework${qs({ view: 'range', from, to, group: session.groupId, subject: subjectId })}`
		).then((r) => (items = r));
	});

	function shift(n: number) {
		const d = new Date(year, month + n, 1);
		year = d.getFullYear();
		month = d.getMonth();
	}
</script>

<div class="cal card">
	<div class="head">
		<button class="nav" onclick={() => shift(-1)} aria-label="Предыдущий месяц"
			><ChevronLeft size={18} /></button
		>
		<strong class="month">{monthName}</strong>
		<button class="nav" onclick={() => shift(1)} aria-label="Следующий месяц"
			><ChevronRight size={18} /></button
		>
	</div>
	<div class="grid" role="grid" aria-label="Календарь дедлайнов">
		{#each ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'] as w (w)}<span class="wd">{w}</span>{/each}
		{#each cells as d (d.getTime())}
			{@const k = startOfDay(d.getTime())}
			{@const list = byDay[k] ?? []}
			<button
				class="cell num"
				class:out={d.getMonth() !== month}
				class:today={k === today}
				class:sel={k === selected}
				onclick={() => (selected = k)}
				aria-label="{d.getDate()}, заданий: {list.length}"
			>
				<span class="n">{d.getDate()}</span>
				<span class="dots">
					{#each list.slice(0, 3) as h (h.id)}<i
							style:background={h.subject.color}
							class:done={h.done}
						></i>{/each}
				</span>
			</button>
		{/each}
	</div>
</div>

{#key selected}
	<div class="day-list" in:fade>
		{#each byDay[selected] ?? [] as h (h.id)}
			<a class="list-row" href="/homework/{h.id}">
				<span class="dot" style:background={h.subject.color}></span>
				<span class="t" class:done={h.done}>{h.title}</span>
				<span class="faint small">{h.subject.name}</span>
			</a>
		{:else}
			<p class="faint empty">В этот день дедлайнов нет</p>
		{/each}
	</div>
{/key}

<style>
	.cal {
		padding: var(--s3);
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: var(--s2);
	}
	.month {
		text-transform: capitalize;
	}
	.nav {
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
	}
	.nav:hover {
		background: var(--surface-2);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(7, 1fr);
		gap: 2px;
	}
	.wd {
		text-align: center;
		font-size: 12px;
		color: var(--text-3);
		padding: 4px 0;
	}
	.cell {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		height: 52px;
		padding-top: 6px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		font-size: 14px;
	}
	.cell:hover {
		background: var(--surface-2);
	}
	.out {
		color: var(--text-3);
	}
	.today .n {
		color: var(--accent);
		font-weight: 700;
	}
	.sel {
		background: var(--accent-soft);
	}
	.dots {
		display: flex;
		gap: 3px;
	}
	.dots i {
		width: 6px;
		height: 6px;
		border-radius: 50%;
	}
	.dots i.done {
		opacity: 0.35;
	}
	.day-list {
		margin-top: var(--s3);
		display: flex;
		flex-direction: column;
		background: var(--surface);
		border-radius: var(--r-l);
		box-shadow: var(--shadow-1);
		overflow: hidden;
	}
	.day-list > * + * {
		border-top: 1px solid var(--border);
	}
	.dot {
		width: 10px;
		height: 10px;
		border-radius: 3px;
		flex: none;
	}
	.t {
		flex: 1;
		min-width: 0;
		font-weight: 550;
	}
	.t.done {
		text-decoration: line-through;
		color: var(--text-3);
	}
	.empty {
		padding: var(--s4);
		text-align: center;
	}
</style>
