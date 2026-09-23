<script lang="ts">
	import { onMount } from 'svelte';
	import {
		AlarmClock,
		Bell,
		BellRing,
		CalendarCheck,
		FileClock,
		FileText,
		Newspaper,
		Sun
	} from '@lucide/svelte';
	import { get } from '$lib/api';
	import { fmtAgo, fmtDate, relativeDay, startOfDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { bell, markAllRead, markRead } from '$lib/notify.svelte';
	import { currentSubscription, pushSupported } from '$lib/push';
	import type { NotificationItem, NotificationPage } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';

	const ICONS = {
		homework: CalendarCheck,
		news: Newspaper,
		material: FileText,
		material_pending: FileClock,
		reminder: AlarmClock,
		digest: Sun,
		test: Bell
	};

	let items = $state<NotificationItem[] | null>(null);
	let next = $state<number | null>(null);
	let loadingMore = $state(false);
	let pushOn = $state(true);

	let unavailable = $state(false);

	async function load(before?: number) {
		let page: NotificationPage;
		try {
			page = await get<NotificationPage>(`/api/notifications${before ? `?before=${before}` : ''}`);
		} catch {
			unavailable = true;
			items ??= [];
			return;
		}
		unavailable = false;
		items = before ? [...(items ?? []), ...page.items] : page.items;
		next = page.next;
		bell.unread = page.unread;
	}

	onMount(() => {
		// Открыли список — значит, увидели: счётчик гаснет, а новые остаются выделенными до ухода.
		load().then(() => {
			if (bell.unread) markAllRead().catch(() => {});
		});
		currentSubscription().then((s) => (pushOn = !pushSupported() || s !== null));
	});

	async function more() {
		if (!next) return;
		loadingMore = true;
		try {
			await load(next);
		} finally {
			loadingMore = false;
		}
	}

	const days = $derived.by(() => {
		const out: { day: number; items: NotificationItem[] }[] = [];
		for (const n of items ?? []) {
			const d = startOfDay(n.createdAt);
			if (out.at(-1)?.day !== d) out.push({ day: d, items: [] });
			out.at(-1)!.items.push(n);
		}
		return out;
	});

	function dayTitle(d: number): string {
		const rel = relativeDay(d);
		return rel === 'сегодня' || rel === 'вчера' ? rel[0].toUpperCase() + rel.slice(1) : fmtDate(d);
	}
</script>

<svelte:head><title>Уведомления · groupbase</title></svelte:head>

<div class="page-head">
	<h1>Уведомления</h1>
	{#if items?.some((n) => !n.read)}
		<Button size="s" onclick={() => markAllRead().then(() => load())}>Прочитать все</Button>
	{/if}
</div>

{#if !pushOn}
	<a class="promo" href="/profile#notifications">
		<span class="circle ink"><BellRing size={19} /></span>
		<span>
			<strong>Получайте уведомления на телефон</strong>
			<span class="muted small">Новые задания и напоминания о сроках — даже когда сайт закрыт</span>
		</span>
	</a>
{/if}

{#if unavailable && !items?.length}
	<div class="card">
		<Empty title="Нужен интернет" text="Уведомления загрузятся, когда появится сеть." />
	</div>
{:else if !items}
	<Skeleton lines={5} />
{:else if items.length === 0}
	<div class="card">
		<Empty
			title="Пока тихо"
			text="Здесь появятся новые задания, новости, материалы и напоминания о сроках."
		/>
	</div>
{:else}
	{#each days as d (d.day)}
		<h2 class="day">{dayTitle(d.day)}</h2>
		<ul class="list">
			{#each d.items as n, i (n.id)}
				{@const Icon = ICONS[n.kind] ?? Bell}
				<li in:fly={{ y: 6, delay: stagger(i, 20) }}>
					<a
						class="list-row item"
						class:unread={!n.read}
						href={n.url}
						onclick={() => !n.read && markRead([n.id]).catch(() => {})}
					>
						<span class="icon" class:warn={n.kind === 'reminder' || n.title.startsWith('Срочно')}
							><Icon size={19} /></span
						>
						<span class="text">
							<strong>{n.title}</strong>
							{#if n.body}<span class="muted small">{n.body}</span>{/if}
						</span>
						<span class="faint small when num">{fmtAgo(n.createdAt)}</span>
						{#if !n.read}<span class="dot" aria-label="не прочитано"></span>{/if}
					</a>
				</li>
			{/each}
		</ul>
	{/each}
	{#if next}
		<div class="more"><Button onclick={more} loading={loadingMore}>Показать ещё</Button></div>
	{/if}
{/if}

<style>
	.promo {
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 16px;
		margin-bottom: var(--s5);
		border-radius: var(--r-l);
		background: var(--inverse);
		color: var(--inverse-text);
	}
	.promo:hover {
		text-decoration: none;
	}
	.promo > span:last-child {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.promo .muted {
		color: var(--inverse-muted);
	}
	.promo .circle {
		background: var(--inverse-text);
		border-color: var(--inverse-text);
		color: var(--inverse);
	}
	.day {
		font-size: 15px;
		color: var(--text-2);
		margin: var(--s5) 0 var(--s2);
	}
	.day:first-of-type {
		margin-top: 0;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.item {
		position: relative;
		align-items: flex-start;
		color: var(--text);
	}
	.item:hover {
		text-decoration: none;
	}
	.icon {
		flex: none;
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: 50%;
		background: var(--surface-2);
		color: var(--text-2);
	}
	.icon.warn {
		background: var(--amber-soft);
		color: var(--amber);
	}
	.text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding-top: 2px;
	}
	.text strong {
		font-weight: 550;
	}
	.unread .text strong {
		font-weight: 700;
	}
	.when {
		flex: none;
		padding-top: 4px;
	}
	.dot {
		position: absolute;
		left: 6px;
		top: 50%;
		width: 7px;
		height: 7px;
		margin-top: -3px;
		border-radius: 50%;
		background: var(--danger);
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--s4);
	}
</style>
