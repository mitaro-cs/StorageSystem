<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount, untrack } from 'svelte';
	import {
		ArrowDown,
		BookOpen,
		CalendarDays,
		ChevronLeft,
		ChevronRight,
		Clock,
		FileText,
		MapPin,
		NotebookPen,
		Plus,
		Upload,
		UserRound
	} from '@lucide/svelte';
	import { del, get, qs } from '$lib/api';
	import { peek, put } from '$lib/cache';
	import { toggleDone } from '$lib/content/homework';
	import HomeworkRow from '$lib/content/HomeworkRow.svelte';
	import { fmtDate, fmtTime, fmtWeekday, plural, relativeDay, startOfDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { offline } from '$lib/offline/engine';
	import { tipOpen } from '$lib/onboarding.svelte';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Homework, Lesson } from '$lib/types';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import {
		addDays,
		byDay,
		dayStats,
		durationText,
		kindsText,
		lessonKind,
		lessonName,
		lessonProgress,
		lessonState,
		shortName,
		untilText,
		weekStart
	} from '$lib/schedule/lessons';

	// Расписание (0.6, по референсу владельца): выбираете день недели — карточка дня (сколько пар и
	// часов, когда начало и конец, окна, что сдать), дальше пары по порядку с преподавателем и
	// аудиторией, внизу — задания со сроком в этот день. Неделя и день — в адресе
	// (?week=2026-09-07&day=2026-09-09): вернулись со страницы пары — тот же день.
	let now = $state(Date.now());
	onMount(() => {
		const t = setInterval(() => (now = Date.now()), 30_000);
		return () => clearInterval(t);
	});
	const today = $derived(startOfDay(now));
	const iso = (ms: number) => {
		const d = new Date(ms);
		const p = (n: number) => String(n).padStart(2, '0');
		return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
	};
	const parse = (v: string | null) => {
		const t = v && /^\d{4}-\d{2}-\d{2}$/.test(v) ? new Date(`${v}T12:00`).getTime() : NaN;
		return Number.isFinite(t) ? startOfDay(t) : null;
	};
	const week = $derived(weekStart(parse(page.url.searchParams.get('week')) ?? now));
	const key = () => `schedule:${session.groupId}:${week}`;
	const hwKey = () => `schedule-hw:${session.groupId}:${week}`;
	let lessons = $state<Lesson[] | null>(untrack(() => peek<Lesson[]>(key()) ?? null));
	let homework = $state<Homework[]>(untrack(() => peek<Homework[]>(hwKey()) ?? []));
	let importOpen = $state(false);
	let editorOpen = $state(false);

	async function load() {
		lessons = peek<Lesson[]>(key()) ?? null;
		homework = peek<Homework[]>(hwKey()) ?? [];
		const range = { group: session.groupId, from: week, to: addDays(week, 7) };
		try {
			const [l, h] = await Promise.all([
				get<Lesson[]>(`/api/schedule${qs(range)}`),
				get<Homework[]>(`/api/homework${qs({ view: 'range', ...range })}`).catch(() => null)
			]);
			lessons = put(key(), l);
			if (h) homework = put(hwKey(), h);
		} catch (e) {
			lessons ??= [];
			toastError(e);
		}
	}

	$effect(() => {
		void week;
		void session.groupId;
		void offline.version;
		untrack(load);
	});

	const map = $derived(byDay(lessons ?? []));
	/** Выбранный день: из адреса; иначе сегодня (если неделя эта), иначе первый день с парами. */
	const day = $derived.by(() => {
		const asked = parse(page.url.searchParams.get('day'));
		if (asked !== null && asked >= week && asked < addDays(week, 7)) return asked;
		if (today >= week && today < addDays(week, 7)) return today;
		return [...map.keys()].sort((a, b) => a - b)[0] ?? week;
	});
	const items = $derived(map.get(day) ?? []);
	const stats = $derived(dayStats(items));
	const due = $derived(homework.filter((h) => startOfDay(h.dueAt) === day));
	const subjects = $derived([
		...new Map(items.filter((l) => l.subject).map((l) => [l.subject!.id, l.subject!])).values()
	]);
	const total = $derived(lessons?.length ?? 0);
	const thisWeek = $derived(week === weekStart(now));

	function go(w: number, d: number | null = null) {
		const p: string[] = [];
		if (w !== weekStart(now)) p.push(`week=${iso(w)}`);
		if (d !== null && d !== today) p.push(`day=${iso(d)}`);
		const s = p.join('&');
		goto(`/schedule${s ? `?${s}` : ''}`, { replaceState: true, noScroll: true, keepFocus: true });
	}
	const pick = (d: number) => go(week, d);
	const shiftWeek = (delta: number) => go(addDays(week, delta * 7));
	const toToday = () => go(weekStart(now), today);

	const strip = $derived(
		Array.from({ length: 7 }, (_, i) => {
			const d = addDays(week, i);
			return { day: d, count: map.get(d)?.length ?? 0 };
		})
	);
	const short = (ms: number) =>
		new Date(ms).toLocaleDateString('ru-RU', { weekday: 'short' }).replace('.', '');
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
	const range = $derived.by(() => {
		const end = addDays(week, 6);
		return new Date(week).getMonth() === new Date(end).getMonth()
			? `${new Date(week).getDate()}–${fmtDate(end, now)}`
			: `${fmtDate(week, now)} – ${fmtDate(end, now)}`;
	});
	const weekLabel = $derived.by(() => {
		const n = Math.round((week - weekStart(now)) / (7 * 86_400_000));
		if (n === 0) return 'эта неделя';
		if (n === 1) return 'следующая неделя';
		if (n === -1) return 'прошлая неделя';
		const w = plural(Math.abs(n), ['неделю', 'недели', 'недель']);
		return n > 0 ? `через ${n} ${w}` : `${-n} ${w} назад`;
	});
	const dayWord = $derived(
		Math.abs(day - today) <= 2 * 86_400_000 ? relativeDay(day, now) : fmtWeekday(day)
	);

	/** Строка-подсказка дня: что идёт сейчас, что следующее, или тема первой пары. */
	const note = $derived.by((): { lead: string; text: string } | null => {
		if (day === today) {
			const cur = items.find((l) => lessonState(l, now) === 'now');
			if (cur)
				return {
					lead: lessonName(cur),
					text: `идёт до ${fmtTime(cur.endsAt)}${cur.place ? ` · ${cur.place}` : ''}`
				};
			const next = items.find((l) => l.startsAt > now);
			if (next)
				return {
					lead: lessonName(next),
					text: `${untilText(next.startsAt - now)}${next.place ? `, ${next.place}` : ''}`
				};
			if (items.length) return { lead: 'Пары на сегодня', text: 'закончились — отдыхайте' };
		}
		const topic = items.find((l) => l.note);
		if (topic) return { lead: lessonName(topic), text: topic.note };
		return null;
	});

	// Где вести расписание: выбранная группа или единственная, где это можно.
	const manageGroup = $derived.by(() => {
		const g = currentGroup();
		if (g) return can('manage_schedule', g.id) ? g : null;
		const list = groups().filter((x) => can('manage_schedule', x.id) && !x.archived);
		return list.length === 1 ? list[0] : null;
	});
	const more = $derived.by((): MenuItem[] => {
		const g = manageGroup;
		if (!g) return [];
		return [
			{ label: 'Из файла календаря…', onclick: () => (importOpen = true) },
			{
				label: 'Очистить расписание…',
				danger: true,
				onclick: async () => {
					if (
						!(await ask(
							'Все пары группы исчезнут из расписания. Задания и материалы к ним останутся — просто без пары.',
							{ title: 'Очистить расписание?', ok: 'Очистить', danger: true }
						))
					)
						return;
					try {
						const r = await del<{ deleted: number }>(`/api/groups/${g.id}/schedule`);
						toast(`Удалено ${r.deleted} ${plural(r.deleted, ['пара', 'пары', 'пар'])}`, 'ok');
						load();
					} catch (e) {
						toastError(e);
					}
				}
			}
		];
	});
	const pairs = (n: number) => `${n} ${plural(n, ['пара', 'пары', 'пар'])}`;
</script>

<svelte:head><title>Расписание · groupbase</title></svelte:head>

<div class="page-head">
	<h1>Расписание</h1>
	{#if manageGroup}
		<div class="row head-actions">
			<Button onclick={() => (importOpen = true)}><Upload size={16} /> Из файла календаря</Button>
			<Button variant="primary" onclick={() => (editorOpen = true)}><Plus size={17} /> Пара</Button>
			<Menu items={more} label="Ещё действия с расписанием" />
		</div>
	{/if}
</div>

{#if tipOpen('schedule')}
	{#await import('$lib/tour/Tip.svelte') then m}<m.default
			id="schedule"
			text="Выберите день — увидите, сколько пар, когда начало и конец, окна и что сдать. Пара открывается целиком: тема, задания и материалы."
		/>{/await}
{/if}

<nav class="weeks" aria-label="Неделя">
	<button class="circle" onclick={() => shiftWeek(-1)} aria-label="Предыдущая неделя"
		><ChevronLeft size={20} /></button
	>
	<div class="range">
		<strong class="num">{range}</strong>
		<span class="faint small">{weekLabel}{total ? ` · ${pairs(total)}` : ''}</span>
	</div>
	<button class="circle" onclick={() => shiftWeek(1)} aria-label="Следующая неделя"
		><ChevronRight size={20} /></button
	>
</nav>

<div class="picker" role="group" aria-label="Дни недели">
	{#each strip as d (d.day)}
		<button
			type="button"
			class="dayb"
			class:on={d.day === day}
			class:today={d.day === today}
			class:empty={!d.count}
			aria-pressed={d.day === day}
			onclick={() => pick(d.day)}
			aria-label="{cap(fmtWeekday(d.day))}, {fmtDate(d.day, now)}: {d.count
				? pairs(d.count)
				: 'пар нет'}"
		>
			<span class="wd">{short(d.day)}</span>
			<strong class="num">{new Date(d.day).getDate()}</strong>
			<span class="cnt num">{d.count || '—'}</span>
		</button>
	{/each}
</div>

{#if !lessons}
	<div class="stack"><Skeleton /><Skeleton /></div>
{:else if total === 0 && !due.length}
	<div class="card">
		<Empty
			title={thisWeek ? 'На этой неделе пар нет' : 'В эти дни пар нет'}
			text={manageGroup
				? 'Загрузите расписание из файла календаря (.ics) — например, выгрузку с сайта вуза или из Google Календаря. Пары можно добавить и вручную.'
				: 'Когда староста загрузит расписание, пары появятся здесь — и на «Сегодня».'}
		>
			{#if manageGroup}
				<Button variant="primary" onclick={() => (importOpen = true)}
					><Upload size={16} /> Загрузить файл календаря</Button
				>
			{/if}
			<Button onclick={() => shiftWeek(1)}>Следующая неделя <ChevronRight size={16} /></Button>
		</Empty>
	</div>
{:else}
	{#key day}
		<section
			class="card hero"
			aria-label="{cap(fmtWeekday(day))}, {fmtDate(day, now)}"
			in:fly={{ y: 8 }}
		>
			<div class="stamp" style:--c={subjects[0]?.color ?? '#4f7df5'}>
				<span class="big num">{new Date(day).getDate()}</span>
				<span class="mon">{fmtDate(day, now).replace(/^\d+\s*/, '')}</span>
				{#if day === today}<span class="badge">сегодня</span>{/if}
			</div>
			<div class="about">
				<h2>{cap(dayWord)}</h2>
				<p class="muted">
					{#if stats.count}
						{fmtTime(stats.first!.startsAt)}–{fmtTime(stats.last!.endsAt)}
						· {cap(fmtWeekday(day))}
					{:else}
						{cap(fmtWeekday(day))}, {fmtDate(day, now)}
					{/if}
				</p>
				{#if subjects.length}
					<div class="glyphs" aria-label="Предметы дня">
						{#each subjects.slice(0, 6) as s (s.id)}
							<span class="g" title={s.name}
								><SubjectGlyph id={s.id} name={s.name} color={s.color} size={30} /></span
							>
						{/each}
					</div>
				{/if}
				<div class="chips">
					<span><CalendarDays size={15} /> {stats.count ? pairs(stats.count) : 'пар нет'}</span>
					{#if stats.minutes}<i></i><span><Clock size={15} /> {durationText(stats.minutes)}</span
						>{/if}
				</div>
			</div>
		</section>

		<dl class="facts">
			{#if stats.first}
				<div>
					<dt>Первая пара</dt>
					<dd class="num">
						{fmtTime(stats.first.startsAt)}{stats.first.place ? ` · ${stats.first.place}` : ''}
					</dd>
				</div>
				<div>
					<dt>Последняя заканчивается</dt>
					<dd class="num">{fmtTime(stats.last!.endsAt)}</dd>
				</div>
				<div>
					<dt>Окна</dt>
					<dd class="num">
						{stats.gaps.length
							? stats.gaps.map((g) => `${fmtTime(g.from)}–${fmtTime(g.to)}`).join(', ')
							: 'без окон'}
					</dd>
				</div>
			{/if}
			<div>
				<dt>Сдать в этот день</dt>
				<dd>
					{#if due.length}
						<a href="#due"
							>{due.length}
							{plural(due.length, ['задание', 'задания', 'заданий'])}
							<ArrowDown size={14} /></a
						>
					{:else}
						ничего
					{/if}
				</dd>
			</div>
		</dl>

		{#if note}
			<p class="note"><strong>{note.lead}</strong> — {note.text}</p>
		{/if}

		{#if items.length}
			<div class="sec-head">
				<h2>Пары</h2>
				<span class="faint small">{kindsText(stats.kinds)}</span>
			</div>
			<ol class="pairs">
				{#each items as l, i (l.id)}
					{@const st = lessonState(l, now)}
					{@const k = lessonKind(l.kind)}
					<li class:past={st === 'past'} in:fly={{ y: 8, delay: stagger(i, 40) }}>
						<span class="n num" class:now={st === 'now'} aria-hidden="true">{i + 1}</span>
						<a
							class="pair"
							href="/schedule/{l.id}"
							style:--subject={l.subject?.color ?? 'var(--border-strong)'}
							aria-label="{lessonName(l)}, {fmtTime(l.startsAt)}"
						>
							<span class="time num">
								<strong>{fmtTime(l.startsAt)}</strong>
								<span>{fmtTime(l.endsAt)}</span>
							</span>
							<span class="body">
								<span class="title">
									{#if l.subject}<SubjectGlyph
											id={l.subject.id}
											name={l.subject.name}
											color={l.subject.color}
											size={16}
											bare
										/>{/if}
									<span class="ellipsis">{lessonName(l)}</span>
								</span>
								<span class="meta">
									{#if k.short}<span class="chip">{k.short}</span>{/if}
									{#if l.place}<span><MapPin size={13} /> {l.place}</span>{/if}
									{#if l.homework}<span title="Задания к паре"
											><NotebookPen size={13} /> {l.homework}</span
										>{/if}
									{#if l.materials}<span title="Материалы к паре"
											><FileText size={13} /> {l.materials}</span
										>{/if}
								</span>
								{#if l.teacher}
									<span class="teacher"><UserRound size={13} /> {shortName(l.teacher)}</span>
								{/if}
								{#if l.note}<span class="topic ellipsis">{l.note}</span>{/if}
								{#if st === 'now'}
									<span class="bar" style:--p={lessonProgress(l, now)} aria-label="Идёт сейчас"
									></span>
								{/if}
							</span>
						</a>
					</li>
				{/each}
			</ol>
		{:else}
			<div class="card free">
				<Empty title="Пар нет" text="В этот день занятий нет — можно выдохнуть." />
			</div>
		{/if}

		{#if due.length}
			<section id="due" class="due" aria-label="Сдать в этот день">
				<div class="sec-head">
					<h2>Сдать в этот день</h2>
					<a class="faint small" href="/homework"><BookOpen size={14} /> все задания</a>
				</div>
				<div class="list">
					{#each due as h (h.id)}<HomeworkRow item={h} {now} ontoggle={toggleDone} />{/each}
				</div>
			</section>
		{/if}
	{/key}
{/if}

{#if day !== today}
	<button class="back-today" onclick={toToday} in:fly={{ y: 16 }}>К сегодня</button>
{/if}

<!-- Загрузка файла и форма пары нужны старосте — код грузится по кнопке. -->
{#if importOpen && manageGroup}
	{#await import('$lib/schedule/ScheduleImport.svelte') then m}
		<m.default bind:open={importOpen} group={manageGroup} onsaved={load} />
	{/await}
{/if}
{#if editorOpen && manageGroup}
	{#await import('$lib/schedule/LessonEditor.svelte') then m}
		<m.default bind:open={editorOpen} groupId={manageGroup.id} {day} onsaved={load} />
	{/await}
{/if}

<style>
	.head-actions {
		flex-wrap: wrap;
		gap: var(--s2);
	}
	.weeks {
		display: flex;
		align-items: center;
		gap: var(--s3);
		margin-bottom: var(--s4);
	}
	.range {
		display: flex;
		flex-direction: column;
		min-width: 0;
		line-height: 1.25;
	}
	.range strong {
		font-size: 18px;
		letter-spacing: -0.01em;
	}

	/* Выбор дня: плитки, выбранная — крупнее и светлее, как превью на референсе. */
	.picker {
		display: grid;
		grid-template-columns: repeat(7, 1fr);
		gap: 6px;
		align-items: center;
		margin-bottom: var(--s5);
	}
	.dayb {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1px;
		min-width: 0;
		padding: 8px 0;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		cursor: pointer;
		transition:
			transform 250ms cubic-bezier(0.3, 1.3, 0.5, 1),
			background-color var(--dur) var(--ease),
			box-shadow var(--dur) var(--ease);
	}
	.dayb:hover {
		background: var(--surface-2);
	}
	.dayb .wd {
		font-size: 12px;
		color: var(--text-3);
	}
	.dayb strong {
		font-size: 19px;
		line-height: 1.15;
	}
	.dayb .cnt {
		font-size: 11.5px;
		color: var(--text-3);
	}
	.dayb.empty strong {
		color: var(--text-3);
	}
	.dayb.today .wd {
		color: var(--accent);
		font-weight: 650;
	}
	.dayb.on {
		transform: scale(1.08);
		border-color: transparent;
		background: var(--inverse);
		color: var(--inverse-text);
		box-shadow: var(--shadow-2);
	}
	.dayb.on :is(.wd, .cnt) {
		color: inherit;
		opacity: 0.75;
	}

	/* Карточка дня: светлая на тёмном и наоборот — как на референсе. */
	.hero {
		display: flex;
		gap: var(--s4);
		padding: 10px;
		border-radius: var(--r-xl);
		background: var(--inverse);
		color: var(--inverse-text);
	}
	.stamp {
		position: relative;
		flex: none;
		display: flex;
		flex-direction: column;
		justify-content: flex-end;
		width: clamp(104px, 30%, 150px);
		aspect-ratio: 1;
		padding: 12px;
		border-radius: calc(var(--r-xl) - 8px);
		color: #fff;
		background:
			radial-gradient(90% 70% at 85% 10%, color-mix(in srgb, var(--c) 60%, #fff), transparent),
			linear-gradient(160deg, color-mix(in srgb, var(--c) 92%, #fff), var(--c));
		overflow: hidden;
	}
	.stamp .big {
		font-size: 46px;
		font-weight: 800;
		line-height: 0.95;
		letter-spacing: -0.04em;
	}
	.stamp .mon {
		font-size: 14px;
		font-weight: 600;
		opacity: 0.9;
	}
	.stamp .badge {
		position: absolute;
		top: 10px;
		left: 10px;
		padding: 3px 8px;
		border-radius: 8px;
		background: rgb(0 0 0 / 0.55);
		font-size: 12px;
		font-weight: 650;
		backdrop-filter: blur(6px);
	}
	.about {
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
		padding: 4px 4px 4px 0;
	}
	.about h2 {
		margin: 0;
		font-size: 22px;
		letter-spacing: -0.02em;
	}
	.about p {
		margin: 0;
		color: color-mix(in srgb, var(--inverse-text) 62%, transparent);
	}
	.glyphs {
		display: flex;
		padding-left: 6px;
	}
	.glyphs .g {
		display: grid;
		margin-left: -6px;
		border-radius: 50%;
		background: var(--inverse);
		box-shadow: 0 0 0 2px var(--inverse);
	}
	.glyphs .g :global(.glyph) {
		border-radius: 50%;
	}
	.chips {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 10px;
		margin-top: auto;
		padding: 8px 12px;
		border-radius: var(--r-full);
		background: color-mix(in srgb, var(--inverse-text) 8%, transparent);
		font-size: 13.5px;
		color: color-mix(in srgb, var(--inverse-text) 75%, transparent);
		width: fit-content;
	}
	.chips span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.chips i {
		width: 1px;
		height: 14px;
		background: currentColor;
		opacity: 0.35;
	}

	/* Строки «ключ — значение» под карточкой. */
	.facts {
		margin: var(--s3) 4px 0;
	}
	.facts div {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--s3);
		padding: 11px 0;
		border-bottom: 1px solid var(--border);
	}
	.facts dt {
		color: var(--text-3);
	}
	.facts dd {
		margin: 0;
		font-weight: 600;
		text-align: right;
	}
	.facts a {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.note {
		margin: var(--s4) 4px 0;
		padding-left: 12px;
		border-left: 4px solid var(--accent);
		border-radius: 2px;
		color: var(--text-2);
	}
	.note strong {
		color: var(--text);
	}

	.sec-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--s3);
		margin: var(--s6) 4px var(--s3);
		padding-bottom: var(--s2);
		border-bottom: 1px solid var(--border);
	}
	.sec-head h2 {
		margin: 0;
		font-size: 20px;
	}
	.sec-head a {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}

	/* Пары по порядку: номер в кружке, линия между ними, карточка пары. */
	.pairs {
		margin: 0;
		padding: 0;
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.pairs li {
		position: relative;
		display: grid;
		grid-template-columns: 36px 1fr;
		gap: var(--s3);
	}
	.pairs li:not(:last-child)::before {
		content: '';
		position: absolute;
		left: 17px;
		top: 40px;
		bottom: calc(var(--s3) * -1);
		width: 2px;
		background: var(--border);
	}
	.n {
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		margin-top: 10px;
		border-radius: 50%;
		background: var(--surface-2);
		font-weight: 650;
		font-size: 15px;
	}
	.n.now {
		background: var(--accent);
		color: var(--accent-text);
	}
	.past {
		opacity: 0.6;
	}
	.pair {
		display: flex;
		gap: var(--s4);
		min-width: 0;
		padding: 14px var(--s4);
		border-radius: var(--r-l);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		text-decoration: none;
		transition: background-color var(--dur) var(--ease);
	}
	.pair:hover {
		background: var(--surface-2);
		text-decoration: none;
	}
	.time {
		flex: none;
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		min-width: 46px;
		padding-right: var(--s3);
		border-right: 3px solid var(--subject);
		line-height: 1.2;
	}
	.time strong {
		font-size: 17px;
	}
	.time span {
		font-size: 13px;
		color: var(--text-3);
	}
	.body {
		display: flex;
		flex-direction: column;
		gap: 5px;
		min-width: 0;
		flex: 1;
	}
	.title {
		display: flex;
		align-items: center;
		gap: 7px;
		min-width: 0;
		font-weight: 650;
		font-size: 16px;
	}
	.meta,
	.teacher {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 6px 12px;
		font-size: 13.5px;
		color: var(--text-2);
	}
	.meta span,
	.teacher {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.meta .chip {
		height: 22px;
	}
	.topic {
		font-size: 13.5px;
		color: var(--text-3);
	}
	.bar {
		height: 4px;
		border-radius: 2px;
		background: linear-gradient(
			90deg,
			var(--accent) calc(var(--p) * 100%),
			var(--surface-3) calc(var(--p) * 100%)
		);
	}
	.free {
		margin-top: var(--s5);
	}
	.due {
		margin-bottom: var(--s6);
	}

	/* «К сегодня» — плавающая кнопка внизу, как на референсе. */
	.back-today {
		position: fixed;
		z-index: 30;
		left: 50%;
		bottom: calc(var(--s5) + env(safe-area-inset-bottom));
		translate: -50% var(--vv-shift, 0px);
		padding: 13px 26px;
		border: 0;
		border-radius: var(--r-full);
		background: var(--glass-light);
		color: #0d0d0f;
		font: inherit;
		font-weight: 650;
		font-size: 16px;
		box-shadow: var(--shadow-3);
		backdrop-filter: blur(20px) saturate(1.4);
		-webkit-backdrop-filter: blur(20px) saturate(1.4);
		cursor: pointer;
	}
	@media (max-width: 899px) {
		.back-today {
			bottom: calc(var(--bottom-nav) + var(--bottom-gap) * 2 + env(safe-area-inset-bottom));
		}
		.dayb strong {
			font-size: 17px;
		}
		.about h2 {
			font-size: 19px;
		}
	}
	@media (min-width: 900px) {
		.back-today {
			left: calc(50% + var(--sidebar) / 2);
		}
	}
</style>
