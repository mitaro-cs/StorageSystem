<script lang="ts">
	// Линейный график мониторинга (1.0.2): одна ось, тонкие линии, пропуски – где данных нет,
	// перекрестие и подсказка при наведении. Два ряда – легенда и подпись у конца линии.
	interface Series {
		key: string;
		label: string;
		/** Второй ряд того же показателя (p95 рядом с p50) – пунктиром. */
		dashed?: boolean;
	}

	let {
		points,
		series,
		unit = '',
		label,
		step,
		area = false
	}: {
		points: Record<string, number | null>[];
		series: Series[];
		unit?: string;
		label: string;
		step: number;
		area?: boolean;
	} = $props();

	const W = 640;
	const H = 180;
	const PAD = { l: 44, r: 12, t: 12, b: 24 };

	const max = $derived.by(() => {
		let m = 0;
		for (const p of points) for (const s of series) m = Math.max(m, p[s.key] ?? 0);
		return nice(m || 1);
	});

	/** Верх оси – «круглое» число: 1, 2, 5 × 10ⁿ. */
	function nice(v: number): number {
		const p = 10 ** Math.floor(Math.log10(v));
		for (const k of [1, 2, 5, 10]) if (k * p >= v) return k * p;
		return 10 * p;
	}

	const x = (i: number) => PAD.l + (i / Math.max(1, points.length - 1)) * (W - PAD.l - PAD.r);
	const y = (v: number) => PAD.t + (1 - v / max) * (H - PAD.t - PAD.b);

	/** Путь линии с разрывами там, где значения нет. */
	function path(key: string): string {
		let d = '';
		let pen = false;
		points.forEach((p, i) => {
			const v = p[key];
			if (v === null || v === undefined) {
				pen = false;
				return;
			}
			d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
			pen = true;
		});
		return d;
	}

	function areaPath(key: string): string {
		const line = path(key);
		if (!line) return '';
		return (
			line.replace(/M([\d.]+),([\d.]+)/g, (_, px, py) => `M${px},${y(0)}L${px},${py}`) + 'V' + y(0)
		);
	}

	// Деление посередине – только целым числом, если ось маленькая: «0, 1, 1» читалось как ошибка.
	const ticks = $derived([0, max / 2, max].filter((t) => max > 2 || Number.isInteger(t)));

	/** Точки без соседей: линией их не нарисовать – кружком. */
	function lone(key: string): number[] {
		const has = (i: number) => i >= 0 && i < points.length && points[i][key] != null;
		return points.flatMap((_, i) => (has(i) && !has(i - 1) && !has(i + 1) ? [i] : []));
	}
	const time = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });
	const xTicks = $derived.by(() => {
		const n = points.length;
		if (n < 2) return [];
		const every = Math.ceil(n / 6);
		const out: number[] = [];
		for (let i = 0; i < n; i += every) out.push(i);
		return out;
	});

	let hover = $state<number | null>(null);
	let svg: SVGSVGElement | undefined = $state();

	function onmove(e: PointerEvent) {
		if (!svg || points.length === 0) return;
		const r = svg.getBoundingClientRect();
		const px = ((e.clientX - r.left) / r.width) * W;
		const i = Math.round(((px - PAD.l) / (W - PAD.l - PAD.r)) * (points.length - 1));
		hover = Math.max(0, Math.min(points.length - 1, i));
	}

	const fmt = (v: number | null | undefined) =>
		v === null || v === undefined ? '–' : `${Math.round(v)}${unit ? ` ${unit}` : ''}`;

	/** Последняя точка с данными – для подписи у конца линии. */
	function lastIndex(key: string): number {
		for (let i = points.length - 1; i >= 0; i--) if (points[i][key] != null) return i;
		return -1;
	}
</script>

<figure class="chart">
	{#if series.length > 1}
		<figcaption class="legend">
			{#each series as s (s.key)}
				<span class="key"><i class:dashed={s.dashed}></i>{s.label}</span>
			{/each}
		</figcaption>
	{/if}
	<svg
		bind:this={svg}
		viewBox="0 0 {W} {H}"
		role="img"
		aria-label={label}
		onpointermove={onmove}
		onpointerleave={() => (hover = null)}
	>
		{#each ticks as t (t)}
			<line class="grid" x1={PAD.l} x2={W - PAD.r} y1={y(t)} y2={y(t)} />
			<text class="tick" x={PAD.l - 6} y={y(t) + 4} text-anchor="end">{Math.round(t)}</text>
		{/each}
		{#each xTicks as i (i)}
			<text class="tick" x={x(i)} y={H - 6} text-anchor="middle"
				>{time.format(points[i].t ?? 0)}</text
			>
		{/each}
		{#each series as s (s.key)}
			{#if area}<path class="area" d={areaPath(s.key)} />{/if}
			<path class="line" class:dashed={s.dashed} d={path(s.key)} />
			{#each lone(s.key) as i (i)}
				<circle
					class="lone"
					class:dashed={s.dashed}
					cx={x(i)}
					cy={y(points[i][s.key] ?? 0)}
					r="3"
				/>
			{/each}
			{#if series.length > 1}
				{@const li = lastIndex(s.key)}
				{#if li >= 0}
					<text class="end" x={x(li) - 4} y={y(points[li][s.key] ?? 0) - 6} text-anchor="end"
						>{s.label}</text
					>
				{/if}
			{/if}
		{/each}
		{#if hover !== null}
			<line class="cross" x1={x(hover)} x2={x(hover)} y1={PAD.t} y2={H - PAD.b} />
			{#each series as s (s.key)}
				{#if points[hover][s.key] != null}
					<circle class="dot" cx={x(hover)} cy={y(points[hover][s.key] ?? 0)} r="4" />
				{/if}
			{/each}
		{/if}
	</svg>
	{#if hover !== null}
		{@const p = points[hover]}
		<div
			class="tip"
			style:left="{(x(hover) / W) * 100}%"
			class:flip={x(hover) > W * 0.65}
			role="status"
		>
			<b class="num"
				>{time.format(p.t ?? 0)}{step > 60_000 ? `–${time.format((p.t ?? 0) + step)}` : ''}</b
			>
			{#each series as s (s.key)}
				<span><i class:dashed={s.dashed}></i>{s.label}: <b class="num">{fmt(p[s.key])}</b></span>
			{/each}
		</div>
	{/if}
</figure>

<style>
	.chart {
		position: relative;
		margin: 0;
	}
	svg {
		display: block;
		width: 100%;
		height: auto;
		touch-action: pan-y;
	}
	.grid {
		stroke: var(--border);
		stroke-width: 1;
	}
	.tick {
		fill: var(--text-3);
		font-size: 11px;
		font-variant-numeric: tabular-nums;
	}
	.line {
		fill: none;
		stroke: var(--accent);
		stroke-width: 2;
		stroke-linejoin: round;
		stroke-linecap: round;
	}
	.line.dashed {
		stroke-dasharray: 5 4;
		opacity: 0.75;
	}
	.lone {
		fill: var(--accent);
	}
	.lone.dashed {
		fill: var(--surface);
		stroke: var(--accent);
		stroke-width: 1.5;
	}
	.area {
		fill: color-mix(in srgb, var(--accent) 14%, transparent);
	}
	.end {
		fill: var(--text-2);
		font-size: 11px;
		font-weight: 600;
	}
	.cross {
		stroke: var(--text-3);
		stroke-width: 1;
		stroke-dasharray: 2 3;
	}
	.dot {
		fill: var(--accent);
		stroke: var(--surface);
		stroke-width: 2;
	}
	.legend {
		display: flex;
		gap: 14px;
		margin-bottom: 6px;
		color: var(--text-2);
		font-size: 12.5px;
	}
	.key,
	.tip span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.key i,
	.tip i {
		width: 14px;
		height: 0;
		border-top: 2px solid var(--accent);
	}
	.key i.dashed,
	.tip i.dashed {
		border-top-style: dashed;
		opacity: 0.75;
	}
	.tip {
		position: absolute;
		top: 4px;
		transform: translateX(12px);
		display: flex;
		flex-direction: column;
		gap: 3px;
		padding: 8px 10px;
		border: 1px solid var(--border);
		border-radius: 10px;
		background: var(--surface);
		box-shadow: 0 6px 20px rgb(0 0 0 / 0.12);
		color: var(--text-2);
		font-size: 12.5px;
		white-space: nowrap;
		pointer-events: none;
	}
	.tip.flip {
		transform: translateX(calc(-100% - 12px));
	}
	.tip b {
		color: var(--text);
	}
</style>
