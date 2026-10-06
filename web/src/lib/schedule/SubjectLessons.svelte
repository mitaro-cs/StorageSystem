<script lang="ts">
	import { ChevronLeft, ChevronRight } from '@lucide/svelte';
	import { get, qs } from '$lib/api';
	import { fmtTime, startOfDay } from '$lib/format';
	import { offline } from '$lib/offline/engine';
	import type { Lesson } from '$lib/types';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import LessonCard from './LessonCard.svelte';
	import { KIND_COLORS, addDays, byDay, lessonKind } from './lessons';

	// Вкладка «Пары» предмета (0.9.4): календарь месяца – в клетке время и вид пар, ниже – пары
	// выбранного дня с темами, заданиями и материалами.
	let { subjectId }: { subjectId: number } = $props();

	const DAY = 86_400_000;
	const now = Date.now();
	const today = startOfDay(now);
	const NAMES = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
	let list = $state<Lesson[] | null>(null);
	let month = $state(0);
	let picked = $state(0);

	async function load() {
		try {
			list = await get<Lesson[]>(
				`/api/schedule${qs({ subject: subjectId, from: now - 200 * DAY, to: now + 200 * DAY })}`
			);
		} catch {
			list ??= [];
		}
		// Сначала – день ближайшей пары (или последней, если все прошли).
		if (!picked && list.length) {
			const next = list.find((l) => l.endsAt > now) ?? list[list.length - 1];
			picked = startOfDay(next.startsAt);
			month = firstOfMonth(picked);
		}
	}

	$effect(() => {
		void subjectId;
		void offline.version;
		load();
	});

	function firstOfMonth(ms: number) {
		const d = new Date(ms);
		return new Date(d.getFullYear(), d.getMonth(), 1).getTime();
	}
	function shift(n: number) {
		const d = new Date(month);
		month = new Date(d.getFullYear(), d.getMonth() + n, 1).getTime();
	}

	const days = $derived(byDay(list ?? []));
	/** Недели месяца с понедельника: 4–6 строк. */
	const weeks = $derived.by(() => {
		const first = new Date(month);
		const start = addDays(month, -((first.getDay() + 6) % 7));
		const next = new Date(first.getFullYear(), first.getMonth() + 1, 1).getTime();
		const out: number[] = [];
		for (let w = start; w < next; w = addDays(w, 7)) out.push(w);
		return out;
	});
	const title = $derived(
		new Date(month || now).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })
	);
	const dayList = $derived(days.get(picked) ?? []);
	const kindsHere = $derived([...new Set((list ?? []).map((l) => l.kind))]);
	const upcoming = $derived((list ?? []).filter((l) => l.endsAt > now).length);
	const dayTitle = (d: number) =>
		new Date(d).toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' });

	function pick(d: number) {
		picked = d;
		if (firstOfMonth(d) !== month) month = firstOfMonth(d);
	}
</script>

{#if !list}
	<Skeleton lines={4} />
{:else if list.length === 0}
	<div class="card">
		<Empty
			title="Пар в расписании нет"
			text="Когда староста загрузит расписание, пары предмета будут здесь – с темами, заданиями и материалами."
		/>
	</div>
{:else}
	<section class="cal card" aria-label="Календарь пар">
		<header>
			<button class="circle" aria-label="Предыдущий месяц" onclick={() => shift(-1)}
				><ChevronLeft size={18} /></button
			>
			<h2>{title}</h2>
			<button class="circle" aria-label="Следующий месяц" onclick={() => shift(1)}
				><ChevronRight size={18} /></button
			>
			<button class="pill today-btn" onclick={() => pick(today)}>Сегодня</button>
			<span class="grow"></span>
			<span class="faint small count">впереди пар: <b class="num">{upcoming}</b></span>
			<a class="more-link" href="/schedule">Всё расписание</a>
		</header>
		<div class="grid" role="grid" aria-label={title}>
			<div class="row head" role="row">
				{#each NAMES as n, i (n)}<span role="columnheader" class:we={i > 4}>{n}</span>{/each}
			</div>
			{#each weeks as w (w)}
				<div class="row" role="row">
					{#each Array.from({ length: 7 }, (_, i) => addDays(w, i)) as d (d)}
						{@const ls = days.get(d) ?? []}
						<button
							role="gridcell"
							class="day"
							class:other={firstOfMonth(d) !== month}
							class:today={d === today}
							class:past={d < today}
							class:on={d === picked}
							class:has={ls.length > 0}
							aria-selected={d === picked}
							aria-label="{new Date(d).toLocaleDateString('ru-RU', {
								day: 'numeric',
								month: 'long'
							})}: {ls.length ? `пар – ${ls.length}` : 'пар нет'}"
							onclick={() => pick(d)}
						>
							<span class="n num">{new Date(d).getDate()}</span>
							{#each ls.slice(0, 3) as l (l.id)}
								<span class="ev num" class:off={l.cancelled} style:--k={KIND_COLORS[l.kind]}
									><i></i><span class="t">{fmtTime(l.startsAt)}</span><span class="k"
										>{lessonKind(l.kind).short}</span
									></span
								>
							{/each}
							{#if ls.length > 3}<span class="ev more">+{ls.length - 3}</span>{/if}
						</button>
					{/each}
				</div>
			{/each}
		</div>
		{#if kindsHere.length > 1}
			<p class="legend">
				{#each kindsHere as k (k)}<span style:--k={KIND_COLORS[k]}
						><i></i>{lessonKind(k).label}</span
					>{/each}
			</p>
		{/if}
	</section>

	<section class="day-list" aria-live="polite">
		<h3>{dayTitle(picked || today)}</h3>
		{#if dayList.length}
			<div class="list">
				{#each dayList as l (l.id)}<LessonCard lesson={l} {now} />{/each}
			</div>
		{:else}
			<p class="faint">В этот день пар нет.</p>
		{/if}
	</section>
{/if}

<style>
	.cal {
		padding: 14px;
		margin-bottom: var(--s4);
	}
	header {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		margin-bottom: 12px;
	}
	h2 {
		min-width: 150px;
		margin: 0;
		font-size: 18px;
		text-align: center;
		text-transform: capitalize;
	}
	.today-btn {
		height: 32px;
	}
	.grow {
		flex: 1;
	}
	.count b {
		color: var(--text);
	}
	.grid {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.row {
		display: grid;
		grid-template-columns: repeat(7, minmax(0, 1fr));
		gap: 4px;
	}
	.head span {
		padding: 2px 0 4px;
		font-size: 12px;
		font-weight: 650;
		color: var(--text-3);
		text-align: center;
	}
	.head .we {
		color: var(--text-3);
		opacity: 0.7;
	}
	.day {
		display: flex;
		flex-direction: column;
		align-items: stretch;
		gap: 3px;
		min-width: 0;
		min-height: 86px;
		padding: 6px;
		border: 1px solid transparent;
		border-radius: 12px;
		background: var(--surface-2);
		color: var(--text);
		font: inherit;
		text-align: left;
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease);
	}
	.day:hover {
		border-color: var(--border-strong);
	}
	.day.other {
		opacity: 0.45;
	}
	.day.past:not(.on) .ev {
		opacity: 0.6;
	}
	.n {
		align-self: flex-start;
		display: grid;
		place-items: center;
		min-width: 24px;
		height: 24px;
		padding: 0 4px;
		border-radius: 8px;
		font-size: 13px;
		font-weight: 650;
		color: var(--text-2);
	}
	.today .n {
		background: var(--accent);
		color: var(--accent-text);
	}
	.day.on {
		border-color: var(--text);
		background: var(--surface);
		box-shadow: 0 0 0 1px var(--text);
	}
	.ev {
		display: flex;
		align-items: center;
		gap: 4px;
		min-width: 0;
		padding: 2px 5px;
		border-radius: 6px;
		background: color-mix(in srgb, var(--k, var(--text-3)) 16%, transparent);
		font-size: 11.5px;
		font-weight: 600;
		white-space: nowrap;
		overflow: hidden;
	}
	.ev i,
	.legend i {
		flex: none;
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--k);
	}
	.ev .k {
		color: var(--text-2);
		font-weight: 550;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.ev.off {
		text-decoration: line-through;
		opacity: 0.5;
	}
	.ev.more {
		background: none;
		color: var(--text-3);
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		margin: 12px 2px 0;
		font-size: 12.5px;
		color: var(--text-2);
	}
	.legend span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.day-list h3 {
		margin: 0 0 var(--s3);
		font-size: 17px;
	}
	.day-list h3::first-letter {
		text-transform: uppercase;
	}
	/* Телефон: в клетке – только точки видов, время – в списке дня ниже. */
	@media (max-width: 640px) {
		.cal {
			padding: 10px;
		}
		.day {
			min-height: 50px;
			align-items: center;
			padding: 4px 2px;
		}
		.n {
			align-self: center;
		}
		.ev {
			display: none;
		}
		.day.has::after {
			content: '';
			width: 6px;
			height: 6px;
			border-radius: 50%;
			background: var(--accent);
		}
		.count {
			display: none;
		}
	}
</style>
