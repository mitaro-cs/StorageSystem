<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount, untrack } from 'svelte';
	import {
		BookOpen,
		CalendarDays,
		ChevronLeft,
		ChevronRight,
		Clock,
		FileText,
		MapPin,
		NotebookPen,
		Plus,
		Search,
		Upload,
		UserRound,
		X
	} from '@lucide/svelte';
	import { get, qs } from '$lib/api';
	import { peek, put } from '$lib/cache';
	import { fmtDate, fmtTime, fmtWeekday, plural, relativeDay, startOfDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { offline } from '$lib/offline/engine';
	import { tipOpen } from '$lib/onboarding.svelte';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import type { Homework, Lesson } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import {
		KIND_COLORS,
		LESSON_KINDS,
		addDays,
		byDay,
		dayStats,
		durationText,
		kindsText,
		lessonKind,
		lessonMatches,
		lessonName,
		lessonProgress,
		lessonStatus,
		monthWeeks,
		shortName,
		studyWeek,
		weekStart
	} from '$lib/schedule/lessons';

	// Расписание (0.7, как на сайте МТУСИ, но в нашем стиле): вид «День» – карточка дня и пары по
	// порядку, «Неделя» – колонки дней, «Месяц» – сетка с точками видов пар. Сверху – чётность
	// недели, поиск (предмет, преподаватель, аудитория) и фильтр по виду. Вид, неделя и день – в
	// адресе (?view=week&week=2026-09-07&day=2026-09-09): вернулись со страницы пары – то же место.
	// Время тикает раз в 15 с – идущая пара и полоска видны без перезагрузки.
	let now = $state(Date.now());
	onMount(() => {
		// «Неделя» и «Месяц» – отдельные кусочки: подгружаем заранее, чтобы переключение было мгновенным.
		const warm = setTimeout(() => {
			import('$lib/schedule/WeekView.svelte');
			import('$lib/schedule/MonthView.svelte');
		}, 1500);
		const t = setInterval(() => (now = Date.now()), 15_000);
		return () => {
			clearInterval(t);
			clearTimeout(warm);
		};
	});
	type View = 'day' | 'week' | 'month';
	const VIEWS: { value: View; label: string }[] = [
		{ value: 'day', label: 'День' },
		{ value: 'week', label: 'Неделя' },
		{ value: 'month', label: 'Месяц' }
	];
	const view = $derived.by((): View => {
		const v = page.url.searchParams.get('view');
		return v === 'week' || v === 'month' ? v : 'day';
	});
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
	let query = $state('');
	let kind = $state('');
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
	/** Месяц – по выбранному дню (или по неделе). */
	const anchor = $derived(parse(page.url.searchParams.get('day')) ?? addDays(week, 3));
	const grid = $derived(view === 'month' ? monthWeeks(anchor) : [week]);
	const from = $derived(grid[0]);
	const to = $derived(addDays(grid[grid.length - 1], 7));
	const key = () => `schedule:${session.groupId}:${from}:${to}`;
	const hwKey = () => `schedule-hw:${session.groupId}:${from}:${to}`;
	let lessons = $state<Lesson[] | null>(untrack(() => peek<Lesson[]>(key()) ?? null));
	let homework = $state<Homework[]>(untrack(() => peek<Homework[]>(hwKey()) ?? []));
	let importOpen = $state(false);
	let editorOpen = $state(false);

	async function load() {
		// Пока грузится новый диапазон (смена недели или вида), показываем прежнее – страница не
		// схлопывается в «скелет» и не прыгает (0.9.6, «всё дёргается»).
		lessons = peek<Lesson[]>(key()) ?? lessons;
		homework = peek<Homework[]>(hwKey()) ?? [];
		const range = { group: session.groupId, from, to };
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
		void from;
		void to;
		void session.groupId;
		void offline.version;
		untrack(load);
	});

	const shown = $derived(
		(lessons ?? []).filter((l) => (!kind || l.kind === kind) && lessonMatches(l, query))
	);
	const kindsHere = $derived(LESSON_KINDS.filter((k) => lessons?.some((l) => l.kind === k.value)));
	const map = $derived(byDay(shown));
	const weekLessons = $derived(
		shown.filter((l) => l.startsAt >= week && l.startsAt < addDays(week, 7))
	);
	/** Выбранный день: из адреса; иначе сегодня (если неделя эта), иначе первый день с парами. */
	const day = $derived.by(() => {
		const asked = parse(page.url.searchParams.get('day'));
		if (asked !== null && asked >= week && asked < addDays(week, 7)) return asked;
		if (today >= week && today < addDays(week, 7)) return today;
		return [...map.keys()].sort((a, b) => a - b)[0] ?? week;
	});
	const items = $derived(map.get(day) ?? []);
	const stats = $derived(dayStats(items.filter((l) => !l.cancelled)));
	const due = $derived(homework.filter((h) => startOfDay(h.dueAt) === day));
	const subjects = $derived([
		...new Map(items.filter((l) => l.subject).map((l) => [l.subject!.id, l.subject!])).values()
	]);
	const total = $derived(weekLessons.length);
	const filtered = $derived(!!kind || !!query.trim());
	const thisWeek = $derived(week === weekStart(now));

	function go(w: number, d: number | null = null, v: View = view) {
		const p: string[] = [];
		if (v !== 'day') p.push(`view=${v}`);
		if (w !== weekStart(now)) p.push(`week=${iso(w)}`);
		if (d !== null && d !== today) p.push(`day=${iso(d)}`);
		const s = p.join('&');
		goto(`/schedule${s ? `?${s}` : ''}`, { replaceState: true, noScroll: true, keepFocus: true });
	}
	const pick = (d: number) => go(week, d);
	/** Из недели или месяца – в этот день. */
	const open = (d: number) => go(weekStart(d), d, 'day');
	const shiftWeek = (delta: number) => go(addDays(week, delta * 7));
	function shift(delta: number) {
		if (view !== 'month') return shiftWeek(delta);
		const a = new Date(anchor);
		const first = new Date(a.getFullYear(), a.getMonth() + delta, 1).getTime();
		go(weekStart(first), first);
	}
	const toToday = () => go(weekStart(now), today);
	const setView = (v: View) => go(week, view === 'month' ? null : day, v);
	const parity = $derived(studyWeek(view === 'month' ? now : week));
	/** «Идёт нечётная неделя (5)» – как на сайте вуза; у другой недели – «Чётная неделя (6)». */
	const parityText = $derived.by(() => {
		const w = `${parity.odd ? 'нечётная' : 'чётная'} неделя (${parity.n})`;
		return view === 'month' || thisWeek ? `Идёт ${w}` : cap(w);
	});
	const monthTitle = $derived(
		cap(
			new Date(anchor)
				.toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })
				.replace(/\s*г\.$/, '')
		)
	);
	/** Точки видов пар под днём – как на сайте вуза. */
	// Точка на каждую пару дня (не на вид): пять пар – пять точек.
	const dots = (d: number) =>
		(map.get(d) ?? [])
			.filter((l) => !l.cancelled)
			.map((l) => l.kind)
			.slice(0, 6);

	async function setCancelled(l: Lesson, value: boolean) {
		const updated = await (await import('$lib/schedule/manage')).setCancelled(l, value);
		if (updated) lessons = (lessons ?? []).map((x) => (x.id === l.id ? { ...x, ...updated } : x));
	}

	const strip = $derived(
		Array.from({ length: 7 }, (_, i) => {
			const d = addDays(week, i);
			return { day: d, count: map.get(d)?.length ?? 0 };
		})
	);
	const short = (ms: number) =>
		new Date(ms).toLocaleDateString('ru-RU', { weekday: 'short' }).replace('.', '');
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
				onclick: () =>
					import('$lib/schedule/manage')
						.then((m) => m.clearSchedule(g.id))
						.then((ok) => {
							if (ok) load();
						})
			}
		];
	});
	const pairs = (n: number) => `${n} ${plural(n, ['пара', 'пары', 'пар'])}`;
</script>

<svelte:head><title>Расписание · Campus</title></svelte:head>

<div class="page-head">
	<h1>Расписание</h1>
	{#if manageGroup}
		<div class="row head-actions">
			<Button onclick={() => (importOpen = true)}
				><Upload size={16} /> Из файла<span class="long"> календаря</span></Button
			>
			<Button variant="primary" onclick={() => (editorOpen = true)}><Plus size={17} /> Пара</Button>
			<Menu items={more} label="Ещё действия с расписанием" />
		</div>
	{/if}
</div>

{#if tipOpen('schedule')}
	{#await import('$lib/tour/Tip.svelte') then m}<m.default
			id="schedule"
			text="Выберите день – увидите, сколько пар, когда начало и конец, окна и что сдать. Пара открывается целиком: тема, задания и материалы."
		/>{/await}
{/if}

<div class="bar-top">
	<p class="parity">
		<span class="pdot" class:odd={parity.odd}></span>{parityText}
	</p>
	<div class="views" role="radiogroup" aria-label="Вид расписания">
		{#each VIEWS as v (v.value)}
			<button
				type="button"
				role="radio"
				aria-checked={view === v.value}
				class:on={view === v.value}
				onclick={() => setView(v.value)}>{v.label}</button
			>
		{/each}
	</div>
</div>
<div class="filters">
	<label class="find">
		<Search size={17} />
		<input
			class="input"
			type="search"
			bind:value={query}
			placeholder="Поиск пары"
			aria-label="Поиск по расписанию"
		/>
	</label>
	<select class="select" bind:value={kind} aria-label="Вид занятий">
		<option value="">Все занятия</option>
		{#each kindsHere as k (k.value)}<option value={k.value}
				>{k.forms[2] === 'занятий' ? 'Другие' : cap(k.forms[1])}</option
			>{/each}
	</select>
</div>

<nav class="weeks" aria-label={view === 'month' ? 'Месяц' : 'Неделя'}>
	<button
		class="circle"
		onclick={() => shift(-1)}
		aria-label={view === 'month' ? 'Предыдущий месяц' : 'Предыдущая неделя'}
		><ChevronLeft size={20} /></button
	>
	<div class="range">
		{#if view === 'month'}
			<strong>{monthTitle}</strong>
			<span class="faint small"
				>{pairs(
					shown.filter((l) => new Date(l.startsAt).getMonth() === new Date(anchor).getMonth())
						.length
				)}</span
			>
		{:else}
			<strong class="num">{range}</strong>
			<span class="faint small">{weekLabel}{total ? ` · ${pairs(total)}` : ''}</span>
		{/if}
	</div>
	<button
		class="circle"
		onclick={() => shift(1)}
		aria-label={view === 'month' ? 'Следующий месяц' : 'Следующая неделя'}
		><ChevronRight size={20} /></button
	>
</nav>

{#if view === 'week' && lessons}
	{#await import('$lib/schedule/WeekView.svelte') then m}
		<m.default lessons={weekLessons} {week} {now} onday={open} />
	{/await}
{:else if view === 'month' && lessons}
	{#await import('$lib/schedule/MonthView.svelte') then m}
		<m.default {map} weeks={grid} {anchor} {now} onday={open} />
	{/await}
{:else}
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
				<span class="dots" aria-hidden="true"
					>{#each dots(d.day) as k, j (j)}<i style:--k={KIND_COLORS[k]}></i>{:else}<span class="cnt"
							>–</span
						>{/each}</span
				>
			</button>
		{/each}
	</div>

	{#if !lessons}
		<div class="stack"><Skeleton /><Skeleton /></div>
	{:else if total === 0 && !due.length}
		{#await import('$lib/schedule/EmptyWeek.svelte') then m}<m.default
				{filtered}
				{thisWeek}
				manage={!!manageGroup}
				onimport={() => (importOpen = true)}
				onnext={() => shiftWeek(1)}
			/>{/await}
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

			{#await import('$lib/schedule/DayFacts.svelte') then m}<m.default
					{items}
					{stats}
					due={due.length}
					isToday={day === today}
					{now}
				/>{/await}

			{#if items.length}
				<div class="sec-head">
					<h2>Пары</h2>
					<span class="faint small">{kindsText(stats.kinds)}</span>
				</div>
				<ol class="pairs">
					{#each items as l, i (l.id)}
						{@const st = lessonStatus(l, now)}
						{@const k = lessonKind(l.kind)}
						<li
							class:past={st === 'past'}
							class:cancelled={st === 'cancelled'}
							class:live={st === 'now'}
							in:fly={{ y: 8, delay: stagger(i, 40) }}
						>
							<span class="n num" class:now={st === 'now'} aria-hidden="true">{i + 1}</span>
							<a
								class="pair"
								href="/schedule/{l.id}"
								style:--subject={l.subject?.color ?? 'var(--border-strong)'}
								style:--k={KIND_COLORS[l.kind]}
								aria-label="{lessonName(l)}, {fmtTime(l.startsAt)}{st === 'cancelled'
									? ', пары не было'
									: ''}"
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
										{#if st === 'cancelled'}<span class="chip off">не было</span>
										{:else if st === 'now'}<span class="chip live">идёт</span>
										{:else if st === 'past'}<span class="chip done">прошла</span>{/if}
										{#if k.short}<span class="chip kind">{k.short}</span>{/if}
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
							{#if l.can.edit && (st === 'cancelled' || l.startsAt - now < 14 * 86_400_000)}
								<button
									class="cancel"
									onclick={() => setCancelled(l, !l.cancelled)}
									title={l.cancelled ? 'Вернуть пару' : 'Пары не было'}
									aria-label={l.cancelled ? 'Вернуть пару' : 'Пары не было'}
									>{#if l.cancelled}<span class="undo">↺</span>{:else}<X size={16} />{/if}</button
								>
							{/if}
						</li>
					{/each}
				</ol>
			{:else}
				<div class="card free">
					<Empty title="Пар нет" text="В этот день занятий нет – можно выдохнуть." />
				</div>
			{/if}

			{#if due.length}
				<section id="due" class="due" aria-label="Сдать в этот день">
					<div class="sec-head">
						<h2>Сдать в этот день</h2>
						<a class="faint small" href="/homework"><BookOpen size={14} /> все задания</a>
					</div>
					{#await import('$lib/schedule/DueList.svelte') then m}<m.default
							items={due}
							{now}
						/>{/await}
				</section>
			{/if}
		{/key}
	{/if}
{/if}

{#if view === 'day' && day !== today}
	<button class="back-today" onclick={toToday} in:fly={{ y: 16 }}>К сегодня</button>
{/if}

<!-- Загрузка файла и форма пары нужны старосте – код грузится по кнопке. -->
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
	/* Телефон: три кнопки в одну строку, «⋮» не уезжает отдельно. */
	@media (max-width: 480px) {
		.head-actions {
			flex-wrap: nowrap;
		}
		.long {
			display: none;
		}
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

	/* Выбор дня: плитки, выбранная – крупнее и светлее, как превью на референсе. */
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

	/* Карточка дня: светлая на тёмном и наоборот – как на референсе. */
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
	.past .pair {
		opacity: 0.55;
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

	/* Панель как на сайте вуза: чётность и вид, ниже – поиск и фильтр. */
	.bar-top {
		display: flex;
		align-items: center;
		justify-content: space-between;
		flex-wrap: wrap;
		gap: var(--s3);
		margin-bottom: var(--s3);
	}
	.parity {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		margin: 0;
		font-weight: 650;
	}
	.pdot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: #4f7df5;
		box-shadow: 0 0 0 4px color-mix(in srgb, #4f7df5 22%, transparent);
	}
	.pdot.odd {
		background: #1fa37a;
		box-shadow: 0 0 0 4px color-mix(in srgb, #1fa37a 22%, transparent);
	}
	.views {
		display: inline-flex;
		padding: 3px;
		border-radius: var(--r-full);
		background: var(--surface-2);
	}
	.views button {
		padding: 7px 16px;
		border: 0;
		border-radius: var(--r-full);
		background: none;
		color: var(--text-2);
		font: inherit;
		font-weight: 600;
		white-space: nowrap;
		cursor: pointer;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.views button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
	.filters {
		display: flex;
		gap: var(--s2);
		margin-bottom: var(--s4);
	}
	.find {
		position: relative;
		flex: 1;
		min-width: 0;
		color: var(--text-3);
	}
	.find :global(svg) {
		position: absolute;
		left: 12px;
		top: 50%;
		translate: 0 -50%;
		pointer-events: none;
	}
	.find .input {
		width: 100%;
		padding-left: 38px;
	}
	.filters .select {
		flex: none;
		width: auto;
		max-width: 44%;
	}
	.dots {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: 2px;
		max-width: 100%;
		min-height: 14px;
		align-items: center;
	}
	.dots i {
		width: 5px;
		height: 5px;
		border-radius: 50%;
		background: var(--k);
	}

	/* Состояния пар: прошла – тише, идёт – обведена, не было – зачёркнута. */
	.pairs li.cancelled .pair {
		opacity: 0.6;
		background: repeating-linear-gradient(
			-45deg,
			var(--surface),
			var(--surface) 8px,
			var(--surface-2) 8px,
			var(--surface-2) 16px
		);
	}
	.pairs li.cancelled .title {
		text-decoration: line-through;
		text-decoration-thickness: 2px;
	}
	.pairs li.live .pair {
		box-shadow:
			0 0 0 2px var(--accent),
			var(--shadow-2);
	}
	.pair {
		position: relative;
		overflow: hidden;
	}
	.pair::after {
		content: '';
		position: absolute;
		right: 0;
		top: 0;
		bottom: 0;
		width: 5px;
		background: var(--k);
	}
	.meta .chip.off {
		background: color-mix(in srgb, var(--danger) 16%, transparent);
		color: var(--danger);
	}
	.meta .chip.live {
		background: var(--accent);
		color: var(--accent-text);
	}
	.meta .chip.done {
		background: var(--surface-3);
		color: var(--text-3);
	}
	.cancel {
		position: absolute;
		z-index: 2;
		top: 10px;
		right: 14px;
		display: grid;
		place-items: center;
		width: 32px;
		height: 32px;
		border: 0;
		border-radius: 50%;
		background: var(--surface-2);
		color: var(--text-2);
		cursor: pointer;
		opacity: 0.75;
		transition: opacity var(--dur) var(--ease);
	}
	.undo {
		font-size: 18px;
		line-height: 1;
	}
	.cancel:hover,
	.cancel:focus-visible {
		opacity: 1;
		color: var(--danger);
	}
	.pairs li:has(.cancel) .body {
		padding-right: 34px;
	}

	/* «К сегодня» – плавающая кнопка внизу, как на референсе. */
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
