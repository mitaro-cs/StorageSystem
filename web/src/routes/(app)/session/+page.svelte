<script lang="ts">
	import { untrack } from 'svelte';
	import { CalendarRange, Plus, MapPin } from '@lucide/svelte';
	import { offline } from '$lib/offline/engine';
	import { get, qs } from '$lib/api';
	import { peek, put } from '$lib/cache';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { fmtDate, fmtTime, plural, relativeDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { toggleDone } from '$lib/content/homework';
	import {
		datesFor,
		daysUntil,
		nextExam,
		phase,
		progress,
		sessionExams
	} from '$lib/content/session';
	import type { Homework } from '$lib/types';
	import KindBadge from '$lib/content/KindBadge.svelte';
	import SubjectTag from '$lib/ui/SubjectTag.svelte';
	import DoneToggle from '$lib/ui/DoneToggle.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	const now = Date.now();
	const key = () => `exams:${session.groupId}`;
	let all = $state<Homework[] | null>(untrack(() => peek<Homework[]>(key()) ?? null));
	let datesOpen = $state(false);
	let composer = $state(false);

	async function load() {
		all = put(
			key(),
			await get<Homework[]>(`/api/homework${qs({ view: 'exams', group: session.groupId })}`)
		);
	}

	$effect(() => {
		void session.groupId;
		void offline.version;
		load();
	});

	const dates = $derived(datesFor(groups(), session.groupId));
	// Даты задаются для конкретной группы: в режиме «все группы» — только если она одна.
	const target = $derived(currentGroup() ?? (groups().length === 1 ? groups()[0] : undefined));
	const p = $derived(phase(dates, now));
	const list = $derived(all ? sessionExams(all, p.kind === 'after' ? null : dates) : []);
	const next = $derived(nextExam(list, now));
	const done = $derived(progress(list));
	const monthDay = new Intl.DateTimeFormat('ru-RU', { month: 'short' });
	const weekday = new Intl.DateTimeFormat('ru-RU', { weekday: 'short' });

	const status = $derived(
		p.kind === 'before'
			? `начнётся через ${p.days} ${plural(p.days, ['день', 'дня', 'дней'])}`
			: p.kind === 'during'
				? `идёт · день ${p.day} из ${p.total}`
				: p.kind === 'after'
					? 'закончилась'
					: ''
	);
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

	function when(h: Homework): string {
		if (h.done) return 'сдано';
		const d = daysUntil(h.dueAt, now);
		if (d < 0) return 'прошло';
		return relativeDay(h.dueAt, now);
	}
</script>

<svelte:head><title>Сессия · groupbase</title></svelte:head>

<div class="page-head">
	<h1>Сессия</h1>
	<div class="row wrap actions">
		{#if target && can('manage_subjects', target.id)}
			<Button onclick={() => (datesOpen = true)}><CalendarRange size={17} /> Даты</Button>
		{/if}
		{#if can('publish_homework')}
			<Button variant="primary" onclick={() => (composer = true)}
				><Plus size={17} /> Зачёт или экзамен</Button
			>
		{/if}
	</div>
</div>

{#if dates}
	<p class="dates muted">
		<span class="num">{fmtDate(dates.from, now)} — {fmtDate(dates.to, now)}</span>{status
			? ` · ${status}`
			: ''}
	</p>
{/if}

{#if all === null}
	<div class="stack"><Skeleton /><Skeleton /></div>
{:else if list.length === 0}
	<div class="card">
		<Empty
			title="Зачётов и экзаменов пока нет"
			text="Добавьте их как задания с типом «Зачёт» или «Экзамен» — здесь появится расписание с обратным отсчётом, а на главной — карточка сессии."
		/>
	</div>
{:else}
	<section class="hero" in:fly={{ y: 10 }}>
		<div class="countdown">
			{#if next}
				{@const left = daysUntil(next.dueAt, now)}
				<span class="kicker"
					>Ближайший {next.kind === 'exam' ? 'экзамен' : 'зачёт'}: {next.subject.name}</span
				>
				{#if left <= 1}
					<strong class="big">{left === 0 ? 'Сегодня' : 'Завтра'}</strong>
				{:else}
					<strong class="big num">{left}</strong>
					<span class="unit">{plural(left, ['день', 'дня', 'дней'])}</span>
				{/if}
			{:else}
				<span class="kicker">Итог</span>
				<strong class="big">Всё сдано</strong>
			{/if}
		</div>
		<div class="score">
			<span class="ring" style:--p={done.total ? done.done / done.total : 0}>
				<span class="num">{done.done}<span class="of">/{done.total}</span></span>
			</span>
			<span class="muted small">сдано</span>
		</div>
	</section>

	<ol class="plan">
		{#each list as h, i (h.id)}
			{@const past = !h.done && daysUntil(h.dueAt, now) < 0}
			<li class:done={h.done} class:past in:fly={{ y: 8, delay: stagger(i) }}>
				<span class="date">
					<strong class="num">{new Date(h.dueAt).getDate()}</strong>
					<span>{monthDay.format(h.dueAt).replace('.', '')}</span>
					<span class="faint">{weekday.format(h.dueAt)}</span>
				</span>
				<a class="body" href="/homework/{h.id}">
					<span class="tags"><KindBadge kind={h.kind} compact /><SubjectTag {...h.subject} /></span>
					<span class="title">{h.title}</span>
					<span class="meta num">
						<span>{fmtTime(h.dueAt)}</span>
						{#if h.place}<span class="place"><MapPin size={13} />{h.place}</span>{/if}
						<span class="rel" class:soon={!h.done && daysUntil(h.dueAt, now) <= 1 && !past}
							>{cap(when(h))}</span
						>
					</span>
				</a>
				<DoneToggle done={h.done} label="Сдано: {h.title}" onchange={(v) => toggleDone(h, v)} />
			</li>
		{/each}
	</ol>
	<p class="hint">Отметка «сдано» — только ваша, её никто не видит.</p>
{/if}

{#if datesOpen && target}
	{#await import('$lib/content/SessionDates.svelte') then m}
		<m.default bind:open={datesOpen} groupId={target.id} dates={target.session} />
	{/await}
{/if}
{#if composer}
	{#await import('$lib/content/HomeworkComposer.svelte') then m}
		<m.default bind:open={composer} initialKind="exam" onsaved={load} />
	{/await}
{/if}

<style>
	.actions {
		gap: var(--s2);
	}
	.dates {
		margin: calc(-1 * var(--s3)) 0 var(--s4);
	}
	.hero {
		display: flex;
		align-items: center;
		gap: var(--s5);
		padding: var(--s5);
		margin-bottom: var(--s5);
		border-radius: var(--r-xl);
		background: var(--inverse);
		color: var(--inverse-text);
		box-shadow: var(--shadow-2);
	}
	.countdown {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 4px 10px;
	}
	.kicker {
		width: 100%;
		color: var(--inverse-muted);
		font-size: 14px;
		line-height: 1.35;
	}
	.big {
		font-size: clamp(40px, 11vw, 64px);
		line-height: 1;
		font-weight: 700;
		letter-spacing: -0.04em;
	}
	.unit {
		font-size: 18px;
		color: var(--inverse-muted);
	}
	.score {
		flex: none;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
	}
	.score .muted {
		color: var(--inverse-muted);
	}
	.ring {
		display: grid;
		place-items: center;
		width: 84px;
		height: 84px;
		border-radius: 50%;
		background:
			radial-gradient(closest-side, var(--inverse) 78%, transparent 80% 100%),
			conic-gradient(var(--ok) calc(var(--p) * 1turn), var(--inverse-2) 0);
		font-size: 22px;
		font-weight: 700;
	}
	.of {
		font-size: 15px;
		color: var(--inverse-muted);
	}
	.plan {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s2);
	}
	.plan li {
		display: flex;
		align-items: center;
		gap: var(--s4);
		padding: 12px var(--s4) 12px 12px;
		border-radius: var(--r);
		background: var(--surface);
		box-shadow: var(--shadow-1);
	}
	.plan li.done,
	.plan li.past {
		opacity: 0.6;
	}
	.date {
		flex: none;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		width: 58px;
		height: 64px;
		border-radius: 14px;
		background: var(--surface-2);
		font-size: 12px;
		line-height: 1.2;
	}
	.date strong {
		font-size: 24px;
		line-height: 1.1;
	}
	.body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
		color: var(--text);
	}
	.body:hover {
		text-decoration: none;
	}
	.tags {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 4px 8px;
	}
	.title {
		font-size: 15.5px;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.done .title {
		text-decoration: line-through;
		text-decoration-color: var(--border-strong);
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 2px 12px;
		color: var(--text-2);
		font-size: 13px;
	}
	.place {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.rel.soon {
		color: var(--amber);
		font-weight: 600;
	}
	.hint {
		margin-top: var(--s4);
		text-align: center;
	}
	@media (max-width: 520px) {
		.hero {
			padding: var(--s4);
			gap: var(--s4);
		}
		.ring {
			width: 72px;
			height: 72px;
			font-size: 19px;
		}
		.plan li {
			gap: var(--s3);
			padding-right: var(--s3);
		}
		.date {
			width: 50px;
		}
	}
</style>
