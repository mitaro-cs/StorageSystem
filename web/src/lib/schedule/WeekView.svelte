<script lang="ts">
	import { MapPin } from '@lucide/svelte';
	import { fmtTime, startOfDay } from '$lib/format';
	import type { Lesson } from '$lib/types';
	import {
		KIND_COLORS,
		addDays,
		byDay,
		lessonKind,
		lessonName,
		lessonProgress,
		lessonStatus
	} from './lessons';

	// «Неделя» на «Расписании» (0.7, как на сайте вуза): колонки дней с карточками пар. На телефоне
	// колонки идут одна под другой. Воскресенье – только если в него есть пары.
	let {
		lessons,
		week,
		now,
		onday
	}: { lessons: Lesson[]; week: number; now: number; onday: (day: number) => void } = $props();

	const map = $derived(byDay(lessons));
	const today = $derived(startOfDay(now));
	const days = $derived(
		Array.from({ length: 7 }, (_, i) => addDays(week, i)).filter((d, i) => i < 6 || map.has(d))
	);
	const head = (d: number) =>
		new Date(d).toLocaleDateString('ru-RU', { weekday: 'short' }).replace('.', '');
	const date = (d: number) =>
		new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
</script>

<div class="week" style:--cols={days.length}>
	{#each days as d (d)}
		{@const list = map.get(d) ?? []}
		<section class="col" class:today={d === today} aria-label="{head(d)} {date(d)}">
			<button class="dh" onclick={() => onday(d)} aria-label="Открыть день {date(d)}">
				<span class="wd">{head(d)}</span>
				<span class="num">{date(d)}</span>
			</button>
			{#each list as l (l.id)}
				{@const st = lessonStatus(l, now)}
				<a
					class="lc st-{st}"
					href="/schedule/{l.id}"
					style:--k={KIND_COLORS[l.kind]}
					aria-label="{lessonName(l)}, {fmtTime(l.startsAt)}{st === 'cancelled'
						? ', пары не было'
						: ''}"
				>
					<strong class="t">{lessonName(l)}</strong>
					<span class="tm num">{fmtTime(l.startsAt)}–{fmtTime(l.endsAt)}</span>
					{#if l.place}<span class="pl"><MapPin size={12} /> {l.place}</span>{/if}
					<span class="kd"
						>{st === 'cancelled' ? 'не было' : lessonKind(l.kind).short || 'Занятие'}</span
					>
					{#if st === 'now'}<span class="pg" style:--p={lessonProgress(l, now)}></span>{/if}
				</a>
			{:else}
				<p class="none">Пар нет</p>
			{/each}
		</section>
	{/each}
</div>

<style>
	.week {
		display: grid;
		grid-template-columns: repeat(var(--cols), minmax(0, 1fr));
		gap: var(--s2);
		margin-bottom: var(--s6);
	}
	.col {
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
		padding: 6px;
		border-radius: var(--r-l);
		background: var(--surface-2);
	}
	.col.today {
		box-shadow: inset 0 0 0 2px var(--accent);
	}
	.dh {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 6px;
		padding: 6px 6px 4px;
		border: 0;
		background: none;
		color: var(--text);
		font: inherit;
		cursor: pointer;
	}
	.dh .wd {
		font-weight: 700;
		text-transform: capitalize;
	}
	.dh .num {
		font-size: 13px;
		color: var(--text-3);
	}
	.col.today .dh .wd {
		color: var(--accent);
	}
	.lc {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 3px;
		padding: 10px 10px 12px;
		border-radius: var(--r);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		text-decoration: none;
		overflow: hidden;
		font-size: 13px;
		transition: background-color var(--dur) var(--ease);
	}
	.lc:hover {
		background: var(--surface-2);
		text-decoration: none;
	}
	.lc::after {
		content: '';
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		height: 4px;
		background: var(--k);
	}
	.t {
		font-size: 14px;
		line-height: 1.25;
		overflow-wrap: break-word;
		hyphens: auto;
	}
	.tm {
		font-weight: 600;
		color: var(--text-2);
	}
	.pl,
	.kd {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		color: var(--text-3);
		overflow-wrap: anywhere;
	}
	.st-past {
		opacity: 0.55;
	}
	.st-cancelled {
		opacity: 0.6;
	}
	.st-cancelled .t {
		text-decoration: line-through;
	}
	.st-cancelled .kd {
		color: var(--danger);
		font-weight: 600;
	}
	.st-now {
		box-shadow:
			0 0 0 2px var(--accent),
			var(--shadow-2);
	}
	.pg {
		height: 3px;
		margin-top: 3px;
		border-radius: 2px;
		background: linear-gradient(
			90deg,
			var(--accent) calc(var(--p) * 100%),
			var(--surface-3) calc(var(--p) * 100%)
		);
	}
	.none {
		margin: 4px 6px 8px;
		font-size: 13px;
		color: var(--text-3);
	}
	@media (max-width: 899px) {
		.week {
			grid-template-columns: 1fr;
		}
		.col .lc {
			font-size: 13.5px;
		}
	}
</style>
