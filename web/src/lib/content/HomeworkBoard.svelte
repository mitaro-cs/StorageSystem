<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { untrack } from 'svelte';
	import { Plus } from '@lucide/svelte';
	import { get, qs } from '$lib/api';
	import { peek, put } from '$lib/cache';
	import { can, session } from '$lib/session.svelte';
	import { fmtDate, fmtWeekday, relativeDay } from '$lib/format';
	import { flip, fly, stagger } from '$lib/motion';
	import type { Homework } from '$lib/types';
	import { byDay, toggleDone } from './homework';
	import HomeworkRow from './HomeworkRow.svelte';
	import Calendar from './Calendar.svelte';
	import Tabs from '$lib/ui/Tabs.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let { subjectId = null, title = true }: { subjectId?: number | null; title?: boolean } = $props();

	type View = 'week' | 'calendar' | 'overdue' | 'all';
	let view = $state<View>(untrack(() => subjectId) === null ? 'week' : 'all');
	const key = () => `hw:${view}:${session.groupId}:${subjectId}`;
	let items = $state<Homework[] | null>(untrack(() => peek<Homework[]>(key()) ?? null));
	let overdueCount = $state(0);
	let composer = $state(false);
	const now = Date.now();

	async function load() {
		if (view === 'calendar') return;
		items = peek<Homework[]>(key()) ?? null;
		items = put(
			key(),
			await get<Homework[]>(
				`/api/homework${qs({ view, group: session.groupId, subject: subjectId })}`
			)
		);
	}

	async function countOverdue() {
		const o = await get<Homework[]>(
			`/api/homework${qs({ view: 'overdue', group: session.groupId, subject: subjectId })}`
		);
		overdueCount = o.length;
	}

	$effect(() => {
		void session.groupId;
		void view;
		void offline.version;
		load();
		countOverdue();
	});

	const tabs = $derived([
		...(subjectId !== null
			? [{ label: 'Все', value: 'all' }]
			: [{ label: 'На неделю', value: 'week' }]),
		{ label: 'Календарь', value: 'calendar' },
		{ label: 'Просрочено', value: 'overdue', count: overdueCount }
	]);

	/** Выполненные опускаются вниз — с анимацией перестановки. */
	const sortDone = (list: Homework[]) =>
		[...list].sort((a, b) => Number(a.done) - Number(b.done) || a.dueAt - b.dueAt);

	async function toggle(h: Homework, done: boolean) {
		await toggleDone(h, done);
		countOverdue();
	}

	// Горячая клавиша «n» и палитра открывают форму через ?new=1.
	$effect(() => {
		if (page.url.searchParams.get('new') === '1') {
			composer = true;
			const url = new URL(page.url);
			url.searchParams.delete('new');
			goto(url.pathname + url.search, { replaceState: true, noScroll: true, keepFocus: true });
		}
	});
</script>

{#if title}
	<div class="page-head">
		<h1>Домашние задания</h1>
		{#if can('publish_homework')}
			<Button variant="primary" onclick={() => (composer = true)}><Plus size={17} /> Задание</Button
			>
		{/if}
	</div>
{:else if can('publish_homework')}
	<div class="row sub-actions">
		<span class="spacer"></span><Button size="s" onclick={() => (composer = true)}
			><Plus size={16} /> Задание</Button
		>
	</div>
{/if}

<Tabs {tabs} value={view} onchange={(v) => (view = v as View)} label="Представление" />

{#if view === 'calendar'}
	<Calendar {subjectId} />
{:else if items === null}
	<div class="stack"><Skeleton /><Skeleton /></div>
{:else if items.length === 0}
	<div class="card">
		<Empty
			title={view === 'overdue' ? 'Просроченных нет' : 'Заданий нет'}
			text={view === 'overdue'
				? 'Всё сдано вовремя. Так держать.'
				: 'Когда появятся задания, они будут здесь.'}
		/>
	</div>
{:else if view === 'week'}
	{#each byDay(items) as d, di (d.day)}
		<p class="day">
			<span class="rel">{relativeDay(d.day, now)}</span><span class="faint num"
				>{fmtWeekday(d.day)}, {fmtDate(d.day)}</span
			>
		</p>
		<div class="list">
			{#each sortDone(d.items) as h, i (h.id)}
				<div in:fly={{ y: 8, delay: stagger(i + di * 2) }} animate:flip>
					<HomeworkRow item={h} {now} ontoggle={toggle} />
				</div>
			{/each}
		</div>
	{/each}
{:else}
	<div class="list">
		{#each items as h, i (h.id)}
			<div in:fly={{ y: 8, delay: stagger(i) }}>
				<HomeworkRow item={h} {now} ontoggle={toggle} />
			</div>
		{/each}
	</div>
{/if}

{#if composer}
	{#await import('./HomeworkComposer.svelte') then m}
		<m.default bind:open={composer} {subjectId} onsaved={load} />
	{/await}
{/if}

<style>
	.day {
		display: flex;
		align-items: baseline;
		gap: 10px;
		margin: var(--s5) 0 var(--s2);
	}
	.day:first-of-type {
		margin-top: 0;
	}
	.rel {
		display: inline-block;
		font-weight: 650;
	}
	.rel::first-letter {
		text-transform: uppercase;
	}
	.sub-actions {
		margin-bottom: var(--s3);
	}
</style>
