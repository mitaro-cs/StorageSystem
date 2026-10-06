<script lang="ts">
	import { startOfDay } from '$lib/format';
	import type { Lesson } from '$lib/types';
	import { KIND_COLORS, addDays, lessonName } from './lessons';
	import { abbr } from './month';

	// «Месяц» на «Расписании» (0.7, как на сайте вуза): сетка Пн–Вс, в клетке – число, точки видов
	// пар и сокращения предметов (ВМ, ОП). Нажали на день – открывается «День».
	let {
		map,
		weeks,
		anchor,
		now,
		onday
	}: {
		map: Map<number, Lesson[]>;
		weeks: number[];
		anchor: number;
		now: number;
		onday: (day: number) => void;
	} = $props();

	const today = $derived(startOfDay(now));
	const month = $derived(new Date(anchor).getMonth());
	const NAMES = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

	function cell(d: number) {
		const list = (map.get(d) ?? []).filter((l) => !l.cancelled);
		const names = [...new Set(list.map((l) => abbr(lessonName(l))))];
		return {
			count: list.length,
			off: (map.get(d)?.length ?? 0) - list.length,
			kinds: [...new Set(list.map((l) => l.kind))].slice(0, 5),
			names: names.slice(0, 3),
			more: Math.max(0, names.length - 3)
		};
	}
</script>

<div class="month" role="grid" aria-label="Месяц">
	<div class="row head" role="row">
		{#each NAMES as n, i (n)}<span role="columnheader" class:sat={i === 5}>{n}</span>{/each}
	</div>
	{#each weeks as w (w)}
		<div class="row" role="row">
			{#each Array.from({ length: 7 }, (_, i) => addDays(w, i)) as d, i (d)}
				{@const c = cell(d)}
				<button
					role="gridcell"
					class="day"
					class:other={new Date(d).getMonth() !== month}
					class:today={d === today}
					class:past={d < today}
					class:sat={i === 5}
					onclick={() => onday(d)}
					aria-label="{new Date(d).toLocaleDateString('ru-RU', {
						day: 'numeric',
						month: 'long'
					})}: {c.count ? `пар – ${c.count}` : 'пар нет'}"
				>
					<span class="n num">{new Date(d).getDate()}</span>
					{#if c.kinds.length}
						<span class="dots"
							>{#each c.kinds as k (k)}<i style:--k={KIND_COLORS[k]}></i>{/each}</span
						>
					{/if}
					<span class="ab">
						{#each c.names as a (a)}<span>{a}</span>{/each}
						{#if c.more}<span class="more">+{c.more}</span>{/if}
						{#if c.off}<span class="off">−{c.off}</span>{/if}
					</span>
				</button>
			{/each}
		</div>
	{/each}
</div>

<style>
	.month {
		display: flex;
		flex-direction: column;
		gap: 4px;
		margin-bottom: var(--s6);
	}
	.row {
		display: grid;
		grid-template-columns: repeat(7, minmax(0, 1fr));
		gap: 4px;
	}
	.head span {
		padding: 4px 0;
		text-align: center;
		font-size: 13px;
		font-weight: 650;
		color: var(--text-3);
	}
	.head .sat {
		color: #d9a21b;
	}
	.day {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 4px;
		min-width: 0;
		min-height: 92px;
		padding: 8px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		text-align: left;
		cursor: pointer;
		transition: background-color var(--dur) var(--ease);
	}
	.day:hover {
		background: var(--surface-2);
	}
	.n {
		font-weight: 700;
		font-size: 15px;
	}
	.day.sat .n {
		color: #d9a21b;
	}
	.day.today {
		border-color: transparent;
		background: color-mix(in srgb, #1fa37a 16%, var(--surface));
		box-shadow: inset 0 0 0 2px #1fa37a;
	}
	.day.today .n {
		color: #1fa37a;
	}
	.day.past:not(.today) {
		opacity: 0.6;
	}
	.day.other {
		opacity: 0.35;
	}
	.dots {
		display: flex;
		flex-wrap: wrap;
		gap: 3px;
	}
	.dots i {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--k);
	}
	.ab {
		display: flex;
		flex-wrap: wrap;
		gap: 2px 6px;
		font-size: 12px;
		font-weight: 600;
		color: var(--text-2);
		line-height: 1.2;
	}
	.ab .more {
		color: var(--text-3);
	}
	.ab .off {
		color: var(--danger);
	}
	@media (max-width: 599px) {
		.row {
			gap: 3px;
		}
		.day {
			min-height: 62px;
			padding: 5px 4px;
			align-items: center;
		}
		.ab {
			display: none;
		}
		.dots {
			justify-content: center;
		}
	}
</style>
