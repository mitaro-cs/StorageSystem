<script lang="ts">
	import type { MonitorPoint } from './types';

	// Доступность полосой (1.0.2): каждая чёрточка – отрезок времени. Состояние – цветом И подписью в
	// подсказке и легенде (не только цветом).
	let { points, step }: { points: MonitorPoint[]; step: number } = $props();

	type State = 'ok' | 'partial' | 'down' | 'none';

	const first = $derived(points.findIndex((p) => p.up > 0 || p.requests > 0 || p.reach != null));

	function state(p: MonitorPoint, i: number): State {
		if (first < 0 || i < first) return 'none';
		if (p.up === 0) return 'down';
		if (p.reach === null) return p.up >= 0.99 ? 'ok' : 'partial';
		if (p.reach === 0) return 'down';
		return p.reach >= 0.99 && p.up >= 0.99 ? 'ok' : 'partial';
	}

	const TEXT: Record<State, string> = {
		ok: 'работал',
		partial: 'были перебои',
		down: 'не работал',
		none: 'нет данных'
	};
	const time = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });
	const span = (p: MonitorPoint) =>
		step > 60_000 ? `${time.format(p.t)}–${time.format(p.t + step)}` : time.format(p.t);
</script>

<div class="uptime">
	<div class="bars" role="list" aria-label="Доступность по времени">
		{#each points as p, i (p.t)}
			{@const s = state(p, i)}
			<span
				class="bar {s}"
				role="listitem"
				title="{span(p)}: {TEXT[s]}"
				aria-label="{span(p)}: {TEXT[s]}"
			></span>
		{/each}
	</div>
	<div class="legend">
		<span><i class="ok"></i>работал</span>
		<span><i class="partial"></i>перебои</span>
		<span><i class="down"></i>не работал</span>
		<span><i class="none"></i>нет данных</span>
	</div>
</div>

<style>
	.bars {
		display: flex;
		gap: 2px;
		height: 34px;
	}
	.bar {
		flex: 1;
		min-width: 2px;
		border-radius: 3px;
		transition: transform 120ms var(--ease);
	}
	.bar:hover {
		transform: scaleY(1.12);
	}
	.ok {
		background: var(--ok);
	}
	.partial {
		background: var(--amber);
	}
	.down {
		background: var(--danger);
	}
	.none {
		background: var(--surface-3);
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 14px;
		margin-top: 8px;
		color: var(--text-3);
		font-size: 12px;
	}
	.legend span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.legend i {
		width: 10px;
		height: 10px;
		border-radius: 3px;
	}
</style>
