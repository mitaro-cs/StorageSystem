<script lang="ts">
	import { CalendarCheck, TriangleAlert } from '@lucide/svelte';
	import { fmtDate, fmtWeekdayShort, plural, relativeDay } from '$lib/format';
	import { describe, peak, weekLoad } from './weekLoad';
	import type { Homework } from '$lib/types';

	// Нагрузка недели (1.0.2): сводка «N на неделе» и полоса из семи дней – высота и цвет столбика
	// по «весу» несделанного (лабораторная и контрольная тяжелее домашнего, зачёт и экзамен – ещё).
	// Нажатие на день – к нему в «Дедлайнах».
	let {
		items,
		overdue,
		now,
		onpick
	}: {
		items: Homework[];
		overdue: number;
		now: number;
		onpick: (day: number) => void;
	} = $props();

	const days = $derived(weekLoad(items, now));
	const open = $derived(items.filter((h) => !h.done).length);
	const top = $derived(peak(days));
	/** Шкала – от веса 4, чтобы одно домашнее не выглядело горой. */
	const scale = $derived(Math.max(4, ...days.map((d) => d.weight)));
	const LEVEL = ['свободно', 'легко', 'средне', 'много', 'завал'];

	/** «сегодня», «завтра» или «пн, 12 октября». */
	function when(day: number): string {
		const r = relativeDay(day, now);
		return r === 'сегодня' || r === 'завтра' ? r : `${fmtWeekdayShort(day)}, ${fmtDate(day, now)}`;
	}

	function label(d: (typeof days)[number]): string {
		const when = `${fmtWeekdayShort(d.day)}, ${fmtDate(d.day, now)}`;
		if (!d.open.length) return d.done ? `${when}: всё сделано` : `${when}: ничего`;
		const n = d.open.length;
		return `${when}: ${n} ${plural(n, ['задание', 'задания', 'заданий'])} (${describe(d.open)}) – ${LEVEL[d.level]}`;
	}
</script>

<div class="load">
	<div class="stats">
		<span><CalendarCheck size={17} /> <strong class="num">{open}</strong> на неделе</span>
		{#if overdue}
			<span class="bad"
				><TriangleAlert size={17} /> <strong class="num">{overdue}</strong> просрочено</span
			>
		{:else}
			<span>{open === 0 ? 'Всё сдано – можно выдохнуть' : 'Без просрочек'}</span>
		{/if}
	</div>

	<div class="strip" role="group" aria-label="Нагрузка по дням">
		{#each days as d, i (d.day)}
			<button
				class="day lv{d.level}"
				class:today={i === 0}
				disabled={!d.open.length && !d.done}
				title={label(d)}
				aria-label={label(d)}
				onclick={() => onpick(d.day)}
			>
				<span class="wd">{i === 0 ? 'сег.' : fmtWeekdayShort(d.day)}</span>
				<span class="track">
					{#if d.weight}
						<span class="n num">{d.open.length}</span>
						<span
							class="bar"
							style:height="{Math.round(12 + (d.weight / scale) * 30)}px"
							style:animation-delay="{i * 40}ms"
						></span>
					{:else if d.done}
						<span class="ok" aria-hidden="true">✓</span>
					{:else}
						<span class="flat"></span>
					{/if}
				</span>
				<span class="dt num">{new Date(d.day).getDate()}</span>
			</button>
		{/each}
	</div>

	<p class="note">
		{#if top}
			Тяжелее всего – <strong>{when(top.day)}</strong>: {describe(top.open)}
		{:else}
			Неделя свободна
		{/if}
	</p>
</div>

<style>
	.load {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.stats {
		align-self: flex-start;
		margin: 0;
		background: transparent;
		padding: 0;
	}
	.stats > span:first-child {
		padding-left: 0;
	}
	.bad,
	.bad strong {
		color: var(--danger);
	}
	.strip {
		display: grid;
		grid-template-columns: repeat(7, minmax(0, 1fr));
		gap: 6px;
		max-width: 560px;
	}
	.day {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		padding: 6px 0;
		border: 0;
		border-radius: 12px;
		background: none;
		color: var(--text-3);
		font: inherit;
		font-size: 12px;
		cursor: pointer;
		transition: background 120ms var(--ease);
	}
	.day:hover:not(:disabled) {
		background: var(--surface-2);
	}
	.day:disabled {
		cursor: default;
	}
	.day:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 1px;
	}
	.today .wd,
	.today .dt {
		color: var(--text);
		font-weight: 700;
	}
	.track {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: flex-end;
		gap: 3px;
		width: 100%;
		height: 58px;
	}
	.n {
		color: var(--text-2);
		font-size: 11px;
		font-weight: 600;
		line-height: 1;
	}
	/* Тёплая шкала (не акцент: в «Классике» он чёрный) – от светло-жёлтого к красному «завалу». */
	.bar {
		width: min(26px, 72%);
		border-radius: 7px 7px 3px 3px;
		background: var(--amber);
		transform-origin: bottom;
		animation: grow 420ms var(--ease) both;
	}
	.lv1 .bar {
		background: color-mix(in srgb, var(--amber) 40%, var(--surface-2));
	}
	.lv2 .bar {
		background: color-mix(in srgb, var(--amber) 75%, var(--surface-2));
	}
	.lv3 .bar {
		background: color-mix(in srgb, var(--amber) 55%, var(--danger));
	}
	.lv4 .bar {
		background: var(--danger);
	}
	.flat {
		width: min(26px, 72%);
		height: 4px;
		border-radius: 2px;
		background: var(--surface-3);
	}
	.ok {
		color: var(--ok);
		font-size: 15px;
		font-weight: 700;
		line-height: 1;
	}
	.note {
		overflow: hidden;
		margin: 0;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--text-2);
		font-size: 13.5px;
	}
	.note strong {
		color: var(--text);
	}
	@keyframes grow {
		from {
			transform: scaleY(0);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.bar {
			animation: none;
		}
	}
</style>
