<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { untrack } from 'svelte';
	import { ChevronLeft, ChevronRight, Plus, Upload } from '@lucide/svelte';
	import { del, get, qs } from '$lib/api';
	import { peek, put } from '$lib/cache';
	import { fmtDate, fmtWeekday, plural, startOfDay } from '$lib/format';
	import { fly, stagger } from '$lib/motion';
	import { offline } from '$lib/offline/engine';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Lesson } from '$lib/types';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import LessonCard from '$lib/schedule/LessonCard.svelte';
	import { addDays, byDay, weekStart } from '$lib/schedule/lessons';
	import { tipOpen } from '$lib/onboarding.svelte';

	// Неделя пар: дни списком сверху вниз, с понедельника по воскресенье, сегодня отмечено. Неделя — в адресе
	// (?week=2026-09-07): вернулись со страницы пары — та же неделя.
	const now = Date.now();
	const iso = (ms: number) => {
		const d = new Date(ms);
		const p = (n: number) => String(n).padStart(2, '0');
		return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
	};
	const week = $derived.by(() => {
		const w = page.url.searchParams.get('week');
		const t = w && /^\d{4}-\d{2}-\d{2}$/.test(w) ? new Date(`${w}T12:00`).getTime() : NaN;
		return weekStart(Number.isFinite(t) ? t : now);
	});
	const key = () => `schedule:${session.groupId}:${week}`;
	let lessons = $state<Lesson[] | null>(untrack(() => peek<Lesson[]>(key()) ?? null));
	let importOpen = $state(false);
	let editorOpen = $state(false);

	async function load() {
		lessons = peek<Lesson[]>(key()) ?? null;
		try {
			lessons = put(
				key(),
				await get<Lesson[]>(
					`/api/schedule${qs({ group: session.groupId, from: week, to: addDays(week, 7) })}`
				)
			);
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

	function go(delta: number) {
		const next = delta === 0 ? weekStart(now) : addDays(week, delta * 7);
		goto(next === weekStart(now) ? '/schedule' : `/schedule?week=${iso(next)}`, {
			replaceState: true,
			noScroll: true,
			keepFocus: true
		});
	}

	// Где вести расписание: выбранная группа или единственная, где это можно.
	const manageGroup = $derived.by(() => {
		const g = currentGroup();
		if (g) return can('manage_schedule', g.id) ? g : null;
		const list = groups().filter((x) => can('manage_schedule', x.id) && !x.archived);
		return list.length === 1 ? list[0] : null;
	});

	const days = $derived.by(() => {
		const map = byDay(lessons ?? []);
		// Все семь дней, пустой — «Пар нет».
		return Array.from({ length: 7 }, (_, i) => addDays(week, i)).map((d) => ({
			day: d,
			items: map.get(d) ?? []
		}));
	});
	const total = $derived(lessons?.length ?? 0);
	// Полоса-календарь недели (удобно на телефоне): день, число и точки по числу пар; нажатие —
	// к этому дню в списке.
	const strip = $derived.by(() => {
		const map = byDay(lessons ?? []);
		return Array.from({ length: 7 }, (_, i) => {
			const day = addDays(week, i);
			return { day, count: map.get(day)?.length ?? 0 };
		});
	});
	const short = (ms: number) =>
		new Date(ms).toLocaleDateString('ru-RU', { weekday: 'short' }).replace('.', '');
	function jump(day: number) {
		document
			.getElementById(`day-${iso(day)}`)
			?.scrollIntoView({ behavior: 'smooth', block: 'start' });
	}
	const thisWeek = $derived(week === weekStart(now));
	const range = $derived.by(() => {
		const end = addDays(week, 6);
		return new Date(week).getMonth() === new Date(end).getMonth()
			? `${new Date(week).getDate()}–${fmtDate(end, now)}`
			: `${fmtDate(week, now)} – ${fmtDate(end, now)}`;
	});
	const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
	// «эта неделя», «следующая неделя», «через 3 недели», «2 недели назад».
	const weekLabel = $derived.by(() => {
		const n = Math.round((week - weekStart(now)) / (7 * 86_400_000));
		if (n === 0) return 'эта неделя';
		if (n === 1) return 'следующая неделя';
		if (n === -1) return 'прошлая неделя';
		const w = plural(Math.abs(n), ['неделю', 'недели', 'недель']);
		return n > 0 ? `через ${n} ${w}` : `${-n} ${w} назад`;
	});

	const more = $derived.by((): MenuItem[] => {
		const g = manageGroup;
		if (!g) return [];
		return [
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
			text="Нажмите на день в полосе — список прокрутится к нему. Пара открывается целиком: тема, задания и материалы к ней."
		/>{/await}
{/if}

<nav class="weeks" aria-label="Неделя">
	<button class="circle" onclick={() => go(-1)} aria-label="Предыдущая неделя"
		><ChevronLeft size={20} /></button
	>
	<div class="range">
		<strong class="num">{range}</strong>
		<span class="faint small"
			>{weekLabel}{total ? ` · ${total} ${plural(total, ['пара', 'пары', 'пар'])}` : ''}</span
		>
	</div>
	<button class="circle" onclick={() => go(1)} aria-label="Следующая неделя"
		><ChevronRight size={20} /></button
	>
	{#if !thisWeek}<button class="pill" onclick={() => go(0)}>Сегодня</button>{/if}
</nav>

<div class="strip" role="group" aria-label="Дни недели">
	{#each strip as d (d.day)}
		<button
			type="button"
			class="dayb"
			class:today={d.day === startOfDay(now)}
			class:empty={!d.count}
			disabled={total === 0}
			onclick={() => jump(d.day)}
			aria-label="{cap(fmtWeekday(d.day))}, {fmtDate(d.day, now)}: {d.count
				? `${d.count} ${plural(d.count, ['пара', 'пары', 'пар'])}`
				: 'пар нет'}"
		>
			<span class="wd">{short(d.day)}</span>
			<strong class="num">{new Date(d.day).getDate()}</strong>
			<span class="dots" aria-hidden="true"
				>{#each Array.from({ length: Math.min(d.count, 4) }, (_, k) => k) as k (k)}<i
					></i>{/each}</span
			>
		</button>
	{/each}
</div>

{#if !lessons}
	<div class="stack"><Skeleton /><Skeleton /></div>
{:else if total === 0}
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
			<Button onclick={() => go(1)}>Следующая неделя <ChevronRight size={16} /></Button>
		</Empty>
	</div>
{:else}
	<div class="days">
		{#each days as d, i (d.day)}
			{@const today = d.day === startOfDay(now)}
			<section
				id="day-{iso(d.day)}"
				class="day"
				class:today
				aria-label="{cap(fmtWeekday(d.day))}, {fmtDate(d.day, now)}"
				in:fly={{ y: 8, delay: stagger(i, 40) }}
			>
				<header class="day-head">
					<strong>{cap(fmtWeekday(d.day))}</strong>
					<span class="faint num">{fmtDate(d.day, now)}</span>
					{#if today}<span class="chip accent">сегодня</span>{/if}
				</header>
				{#if d.items.length}
					<div class="list">
						{#each d.items as l (l.id)}<LessonCard lesson={l} {now} />{/each}
					</div>
				{:else}
					<p class="free faint">Пар нет</p>
				{/if}
			</section>
		{/each}
	</div>
{/if}

<!-- Загрузка файла и форма пары нужны старосте — код грузится по кнопке. -->
{#if importOpen && manageGroup}
	{#await import('$lib/schedule/ScheduleImport.svelte') then m}
		<m.default bind:open={importOpen} group={manageGroup} onsaved={load} />
	{/await}
{/if}
{#if editorOpen && manageGroup}
	{#await import('$lib/schedule/LessonEditor.svelte') then m}
		<m.default
			bind:open={editorOpen}
			groupId={manageGroup.id}
			day={thisWeek ? startOfDay(now) : week}
			onsaved={load}
		/>
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
		margin-bottom: var(--s5);
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
	.strip {
		display: grid;
		grid-template-columns: repeat(7, 1fr);
		gap: 6px;
		margin: calc(var(--s2) * -1) 0 var(--s5);
	}
	.dayb {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 2px;
		min-width: 0;
		padding: 8px 0 7px;
		border: 1px solid var(--border);
		border-radius: var(--r-s);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		cursor: pointer;
		transition: background-color var(--dur) var(--ease);
	}
	.dayb:hover:not(:disabled) {
		background: var(--surface-2);
	}
	.dayb:disabled {
		cursor: default;
		opacity: 0.45;
	}
	.dayb .wd {
		font-size: 12px;
		color: var(--text-3);
	}
	.dayb strong {
		font-size: 17px;
		line-height: 1.2;
	}
	.dayb.today {
		border-color: var(--accent);
		background: var(--accent);
		color: var(--accent-text);
	}
	.dayb.today .wd {
		color: inherit;
		opacity: 0.8;
	}
	.dots {
		display: flex;
		gap: 3px;
		height: 5px;
	}
	.dots i {
		width: 5px;
		height: 5px;
		border-radius: 50%;
		background: currentColor;
		opacity: 0.7;
	}
	.day {
		scroll-margin-top: calc(var(--s4) + env(safe-area-inset-top) + 56px);
	}
	.days {
		display: flex;
		flex-direction: column;
		gap: var(--s5);
	}
	.day-head {
		display: flex;
		align-items: baseline;
		gap: 8px;
		margin: 0 4px var(--s2);
	}
	.day-head strong {
		font-size: 16px;
	}
	.day-head .chip {
		align-self: center;
		margin-left: auto;
		height: 22px;
	}
	.today .list {
		box-shadow:
			0 0 0 2px var(--accent),
			var(--shadow-2);
	}
	.free {
		padding: 14px var(--s4);
		border: 1px dashed var(--border-strong);
		border-radius: var(--r);
		font-size: 14px;
	}
</style>
