<script lang="ts">
	import { Plus, ArrowRight } from '@lucide/svelte';
	import { get } from '$lib/api';
	import { can, session } from '$lib/session.svelte';
	import { fmtDate, fmtWeekday, relativeDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { toggleDone, byDay } from '$lib/content/homework';
	import type { Today } from '$lib/types';
	import HomeworkRow from '$lib/content/HomeworkRow.svelte';
	import NewsCard from '$lib/content/NewsCard.svelte';
	import NewsComposer from '$lib/content/NewsComposer.svelte';
	import HomeworkComposer from '$lib/content/HomeworkComposer.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let data = $state<Today | null>(null);
	let newsOpen = $state(false);
	let hwOpen = $state(false);
	const now = Date.now();

	async function load(group: number | null) {
		data = await get<Today>(`/api/today${group ? `?group=${group}` : ''}`);
	}

	$effect(() => {
		load(session.groupId);
	});

	const firstName = $derived(session.me?.user.displayName.split(' ')[0] ?? '');
	const weekday = fmtWeekday(now);
	const days = $derived(data ? byDay(data.upcoming) : []);
	const openCount = $derived(data ? data.upcoming.filter((h) => !h.done).length : 0);
</script>

<svelte:head><title>Сегодня · groupbase</title></svelte:head>

<header class="hero">
	<p class="eyebrow">{weekday}, <span class="num">{fmtDate(now)}</span></p>
	<h1>Привет, {firstName}</h1>
	{#if data}
		<p class="muted summary">
			{#if openCount === 0 && data.overdue.length === 0}
				На этой неделе всё сдано. Можно выдохнуть.
			{:else}
				На неделе <strong class="num">{openCount}</strong>
				{openCount === 1
					? 'задание'
					: openCount < 5
						? 'задания'
						: 'заданий'}{#if data.overdue.length}, просрочено <strong class="danger num"
						>{data.overdue.length}</strong
					>{/if}.
			{/if}
		</p>
	{/if}
	<div class="actions">
		{#if can('publish_homework')}<Button variant="primary" size="s" onclick={() => (hwOpen = true)}
				><Plus size={16} /> Задание</Button
			>{/if}
		{#if can('publish_news')}<Button size="s" onclick={() => (newsOpen = true)}
				><Plus size={16} /> Новость</Button
			>{/if}
	</div>
</header>

{#if !data}
	<div class="stack"><Skeleton /><Skeleton /></div>
{:else}
	{#if data.overdue.length}
		<section class="block">
			<h2 class="h danger">Просрочено</h2>
			<div class="list">
				{#each data.overdue as h, i (h.id)}
					<div in:fly={{ y: 8, delay: stagger(i) }}>
						<HomeworkRow item={h} {now} ontoggle={toggleDone} />
					</div>
				{/each}
			</div>
		</section>
	{/if}

	<section class="block">
		<div class="h-row">
			<h2 class="h">Ближайшие дедлайны</h2>
			<a class="more" href="/homework">Все задания <ArrowRight size={15} /></a>
		</div>
		{#if days.length === 0}
			<div class="card">
				<Empty
					title="На неделю ничего не задано"
					text="Когда появятся задания, они будут здесь, по дням."
				/>
			</div>
		{:else}
			{#each days as d, di (d.day)}
				<p class="day" in:fly={{ y: 6, delay: stagger(di, 50) }}>
					<span class="rel">{relativeDay(d.day, now)}</span>
					<span class="faint num">{fmtWeekday(d.day)}, {fmtDate(d.day)}</span>
				</p>
				<div class="list">
					{#each d.items as h, i (h.id)}
						<div in:fly={{ y: 8, delay: stagger(i + di * 2) }}>
							<HomeworkRow item={h} {now} ontoggle={toggleDone} />
						</div>
					{/each}
				</div>
			{/each}
		{/if}
	</section>

	<section class="block">
		<div class="h-row">
			<h2 class="h">Новости</h2>
			<a class="more" href="/news">Все новости <ArrowRight size={15} /></a>
		</div>
		<div class="stack">
			{#each [...data.pinned, ...data.news] as n, i (n.id)}
				<div in:fly={{ y: 10, delay: stagger(i, 40) }}><NewsCard item={n} /></div>
			{:else}
				<div class="card"><Empty title="Новостей пока нет" /></div>
			{/each}
		</div>
	</section>
{/if}

<NewsComposer bind:open={newsOpen} onsaved={() => load(session.groupId)} />
<HomeworkComposer bind:open={hwOpen} onsaved={() => load(session.groupId)} />

<style>
	.hero {
		margin-bottom: var(--s6);
	}
	.hero h1 {
		font-size: clamp(28px, 5vw, 36px);
		margin: 6px 0 8px;
	}
	.summary {
		font-size: 16px;
	}
	.summary .danger {
		color: var(--danger);
	}
	.actions {
		display: flex;
		gap: var(--s2);
		margin-top: var(--s4);
	}
	.block {
		margin-bottom: var(--s6);
	}
	.h-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		margin-bottom: var(--s3);
	}
	.h {
		font-size: 18px;
	}
	.h.danger {
		color: var(--danger);
		margin-bottom: var(--s3);
	}
	.more {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: 14px;
		font-weight: 550;
	}
	.day {
		display: flex;
		align-items: baseline;
		gap: 10px;
		margin: var(--s4) 0 var(--s2);
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
</style>
