<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { firstName } from '$lib/names';
	import { Plus, Search, ArrowRight, CalendarCheck, TriangleAlert, Send } from '@lucide/svelte';
	import { get } from '$lib/api';
	import { peek, put } from '$lib/cache';
	import { untrack } from 'svelte';
	import { can, currentGroup, groups, isMulti, session } from '$lib/session.svelte';
	import { fmtDate, fmtWeekday, fmtWeekdayShort, plural, relativeDay } from '$lib/format';
	import { flip, fly, slide, stagger } from '$lib/motion';
	import { toggleDone, byDay } from '$lib/content/homework';
	import { openPalette } from '$lib/shell/palette.svelte';
	import type { Today } from '$lib/types';
	import HomeworkRow from '$lib/content/HomeworkRow.svelte';
	import NewsCard from '$lib/content/NewsCard.svelte';
	import NextDeadline from '$lib/content/NextDeadline.svelte';
	import FirstSteps from '$lib/content/FirstSteps.svelte';
	import NewsComposer from '$lib/content/NewsComposer.svelte';
	import HomeworkComposer from '$lib/content/HomeworkComposer.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let data = $state<Today | null>(untrack(() => peek<Today>(`today:${session.groupId}`) ?? null));
	let newsOpen = $state(false);
	let chatsOpen = $state(false);
	let hwOpen = $state(false);
	const now = Date.now();

	async function load(group: number | null) {
		data = put(`today:${group}`, await get<Today>(`/api/today${group ? `?group=${group}` : ''}`));
	}

	$effect(() => {
		void offline.version;
		load(session.groupId);
	});

	const me = $derived(session.me?.user);
	const name = $derived(firstName(me?.displayName ?? ''));
	const place = $derived(currentGroup()?.name ?? session.me?.groups[0]?.name ?? '');
	const open = $derived(data ? data.upcoming.filter((h) => !h.done) : []);
	const next = $derived(open.length ? open.reduce((a, b) => (b.dueAt < a.dueAt ? b : a)) : null);
	const days = $derived(data ? byDay(data.upcoming) : []);
	const overdue = $derived(data ? data.overdue.filter((h) => !h.done) : []);
	// Новости на главной — сразу под сводкой: срочные первыми, затем закреплённые и свежие.
	const news = $derived.by(() => {
		if (!data) return [];
		const all = [...data.pinned, ...data.news].filter(
			(n, i, a) => a.findIndex((x) => x.id === n.id) === i
		);
		return [...all.filter((n) => n.urgent), ...all.filter((n) => !n.urgent)].slice(0, 3);
	});
	// Чаты в Telegram: выбранной группы, а в режиме «все группы» — всех (с названием группы).
	const chatGroup = $derived(currentGroup() ?? (isMulti() ? null : groups()[0]));
	const chats = $derived(
		(chatGroup ? [chatGroup] : groups()).flatMap((g) =>
			g.chats.map((c) => ({ ...c, label: chatGroup ? c.title : `${c.title} · ${g.name}` }))
		)
	);
	const canPinChats = $derived(!!chatGroup && can('publish_news', chatGroup.id));
	const sortDone = (list: Today['upcoming']) =>
		[...list].sort((a, b) => Number(a.done) - Number(b.done) || a.dueAt - b.dueAt);
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
</script>

<svelte:head><title>Сегодня · groupbase</title></svelte:head>

<header class="hello">
	<div class="who">
		<h1>Привет, {name}</h1>
		<p class="muted">
			{cap(fmtWeekday(now))}, <span class="num">{fmtDate(now)}</span>{place ? ` · ${place}` : ''}
		</p>
	</div>
	{#if me}
		<a class="me" href="/profile" aria-label="Профиль">
			<Avatar id={me.id} name={me.displayName} avatar={me.avatar} size={52} />
		</a>
	{/if}
</header>

<button class="search" onclick={() => openPalette()}>
	<Search size={20} />
	<span>Найти задание, предмет, материал</span>
	<span class="circle ink" aria-hidden="true"><ArrowRight size={18} /></span>
</button>

<div class="chips" role="group" aria-label="Быстрые действия">
	{#if can('publish_homework')}
		<button class="pill ink" onclick={() => (hwOpen = true)}><Plus size={16} /> Задание</button>
	{/if}
	{#if can('publish_news')}
		<button class="pill" onclick={() => (newsOpen = true)}><Plus size={16} /> Новость</button>
	{/if}
	{#each chats as c (c.id)}
		<a class="pill tg" href={c.url} target="_blank" rel="noreferrer"><Send size={15} /> {c.label}</a
		>
	{/each}
	{#if canPinChats}
		<button class="pill ghost" onclick={() => (chatsOpen = true)}
			>{#if chats.length}Чаты…{:else}<Send size={15} /> Закрепить чат{/if}</button
		>
	{/if}
	<a class="pill" href="/homework">Все задания</a>
	<a class="pill" href="/subjects">Предметы</a>
	<a class="pill" href="/materials">Материалы</a>
</div>

<FirstSteps oncreate={() => (hwOpen = true)} />

{#if !data}
	<div class="stack"><Skeleton /><Skeleton /></div>
{:else}
	{#if next}
		<div class="block" in:fly={{ y: 10 }}><NextDeadline item={next} {now} /></div>
	{/if}

	<div class="stats summary">
		<span><CalendarCheck size={17} /> <strong class="num">{open.length}</strong> на неделе</span>
		{#if overdue.length}
			<span class="bad"
				><TriangleAlert size={17} /> <strong class="num">{overdue.length}</strong> просрочено</span
			>
		{:else}
			<span>{open.length === 0 ? 'Всё сдано — можно выдохнуть' : 'Без просрочек'}</span>
		{/if}
	</div>

	{#if news.length}
		<section class="block">
			<div class="section-head">
				<h2>Новости</h2>
				<a class="more-link" href="/news">Все новости</a>
			</div>
			<div class="stack">
				{#each news as n, i (n.id)}
					<div in:fly={{ y: 10, delay: stagger(i, 40) }}><NewsCard item={n} compact /></div>
				{/each}
			</div>
		</section>
	{/if}

	{#if overdue.length}
		<section class="block" out:slide>
			<div class="section-head">
				<h2 class="danger">Просрочено</h2>
				<span class="aside num">{overdue.length}</span>
			</div>
			<div class="list">
				{#each overdue as h, i (h.id)}
					<div in:fly={{ y: 8, delay: stagger(i) }} out:slide animate:flip>
						<HomeworkRow item={h} {now} ontoggle={toggleDone} />
					</div>
				{/each}
			</div>
		</section>
	{/if}

	<section class="block">
		<div class="section-head">
			<h2>Дедлайны</h2>
			<a class="more-link" href="/homework"
				>{data.upcoming.length}
				{plural(data.upcoming.length, ['задание', 'задания', 'заданий'])}</a
			>
		</div>
		{#if days.length === 0}
			<div class="card">
				<Empty
					title="На неделю ничего не задано"
					text="Когда появятся задания, они будут здесь, по дням."
				/>
			</div>
		{:else}
			<ol class="timeline">
				{#each days as d, di (d.day)}
					<li in:fly={{ y: 8, delay: stagger(di, 50) }}>
						<span class="node num" aria-hidden="true">{new Date(d.day).getDate()}</span>
						<div class="day">
							<p class="day-title">
								<strong>{cap(relativeDay(d.day, now))}</strong>
								<span class="faint">{fmtWeekdayShort(d.day)}, {fmtDate(d.day)}</span>
							</p>
							<div class="list">
								{#each sortDone(d.items) as h, i (h.id)}
									<div in:fly={{ y: 8, delay: stagger(i + di * 2) }} animate:flip>
										<HomeworkRow item={h} {now} ontoggle={toggleDone} />
									</div>
								{/each}
							</div>
						</div>
					</li>
				{/each}
			</ol>
		{/if}
	</section>
{/if}

<NewsComposer bind:open={newsOpen} onsaved={() => load(session.groupId)} />
{#if chatGroup && canPinChats && chatsOpen}
	<!-- Окно закрепления чатов нужно только старосте — код грузится по нажатию. -->
	{#await import('$lib/content/GroupChats.svelte') then m}
		<m.default bind:open={chatsOpen} groupId={chatGroup.id} />
	{/await}
{/if}
<HomeworkComposer bind:open={hwOpen} onsaved={() => load(session.groupId)} />

<style>
	.hello {
		display: flex;
		align-items: center;
		gap: var(--s4);
		margin: var(--s2) 0 var(--s5);
	}
	.who {
		flex: 1;
		min-width: 0;
	}
	.hello h1 {
		font-size: clamp(28px, 6vw, 36px);
		margin-bottom: 4px;
	}
	.me {
		flex: none;
		border-radius: 50%;
		box-shadow: 0 0 0 3px var(--surface);
	}
	.search {
		display: flex;
		align-items: center;
		gap: 12px;
		width: 100%;
		height: 60px;
		padding: 0 8px 0 20px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-3);
		font-size: 16px;
		text-align: left;
		transition: box-shadow var(--dur) var(--ease);
	}
	.search:hover {
		box-shadow: var(--shadow-2);
	}
	.search > span:not(.circle) {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.search .circle {
		width: 44px;
		height: 44px;
	}
	.chips {
		display: flex;
		gap: 8px;
		margin: var(--s4) calc(-1 * var(--s4)) var(--s5);
		padding: 2px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
	}
	.chips::-webkit-scrollbar {
		display: none;
	}
	.pill {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 40px;
		padding: 0 18px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font-size: 15px;
		font-weight: 550;
		white-space: nowrap;
	}
	.pill:hover {
		text-decoration: none;
		background: var(--surface-2);
	}
	.pill.ink {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.pill.ink:hover {
		background: var(--accent-hover);
	}
	.pill.tg :global(svg) {
		color: var(--tg);
	}
	.pill.ghost {
		border-style: dashed;
		color: var(--text-2);
	}
	.block {
		margin-bottom: var(--s6);
	}
	.summary {
		margin: calc(-1 * var(--s4)) 0 var(--s5);
		background: transparent;
		padding: 0;
	}
	.summary > span:first-child {
		padding-left: 0;
	}
	.summary .bad,
	.summary .bad strong {
		color: var(--danger);
	}
	.danger {
		color: var(--danger);
	}

	/* Таймлайн по дням: кружок с числом, линия между днями */
	.timeline {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s4);
	}
	.timeline li {
		position: relative;
		display: grid;
		grid-template-columns: 44px 1fr;
		gap: var(--s3);
	}
	.timeline li:not(:last-child)::before {
		content: '';
		position: absolute;
		left: 21px;
		top: 52px;
		bottom: calc(-1 * var(--s4) + 8px);
		width: 2px;
		border-radius: 1px;
		background: var(--border);
	}
	.node {
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border-radius: 50%;
		background: var(--surface);
		box-shadow: var(--shadow-1);
		font-weight: 650;
		font-size: 16px;
	}
	.timeline li:first-child .node {
		background: var(--accent);
		color: var(--accent-text);
	}
	.day {
		min-width: 0;
	}
	.day-title {
		display: flex;
		align-items: baseline;
		gap: 8px;
		min-height: 44px;
		padding-top: 10px;
		margin-bottom: 4px;
	}
	.day-title strong {
		font-size: 17px;
	}
</style>
