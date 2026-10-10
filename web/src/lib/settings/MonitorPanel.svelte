<script lang="ts">
	import { onMount } from 'svelte';
	import { get } from '$lib/api';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { presence, loadPresence } from '$lib/presence.svelte';
	import { firstName } from '$lib/names';
	import LineChart from './monitor/LineChart.svelte';
	import Uptime from './monitor/Uptime.svelte';
	import type { MonitorView } from './monitor/types';

	// Мониторинг хоста (1.0.2): что с сервером сейчас и за час или сутки. Обновляется сам раз в
	// 10 секунд, пока вкладка на экране.
	let range = $state<'hour' | 'day'>('hour');
	let v = $state<MonitorView | null>(null);
	let error = $state('');

	async function load() {
		try {
			v = await get<MonitorView>(`/api/admin/monitor?range=${range}`);
			error = '';
		} catch (e) {
			error = e instanceof Error ? e.message : 'Не удалось загрузить';
		}
	}

	$effect(() => {
		void range;
		load();
	});
	onMount(() => {
		loadPresence(true);
		const t = setInterval(() => {
			if (document.visibilityState === 'visible') {
				load();
				loadPresence();
			}
		}, 10_000);
		return () => clearInterval(t);
	});

	const pct = (x: number | null | undefined) =>
		x === null || x === undefined
			? '–'
			: `${(Math.floor(x * 1000) / 10).toLocaleString('ru-RU')} %`;
	const ms = (x: number | null | undefined) => (x === null || x === undefined ? '–' : `${x}`);
	// Минуты, когда сервер был выключен, – пропуск на графике, а не ноль.
	const pts = $derived(
		(v?.points ?? []).map((p) =>
			p.up > 0 ? { ...p } : { ...p, requests: null, online: null }
		) as unknown as Record<string, number | null>[]
	);
	const onlineNames = $derived(presence.online.map((p) => firstName(p.displayName)).join(', '));
	const heap = $derived(v ? Math.round((v.memory.used / v.memory.max) * 100) : 0);
</script>

<div class="monitor">
	<div class="head">
		<p class="muted small">
			Обновляется сам каждые 10 секунд{v ? ` · сервер запущен ${fmtAgo(v.startedAt)}` : ''}
		</p>
		<div class="seg" role="radiogroup" aria-label="Период">
			<button
				role="radio"
				aria-checked={range === 'hour'}
				class:on={range === 'hour'}
				onclick={() => (range = 'hour')}>Час</button
			>
			<button
				role="radio"
				aria-checked={range === 'day'}
				class:on={range === 'day'}
				onclick={() => (range = 'day')}>Сутки</button
			>
		</div>
	</div>

	{#if error}<p class="error-text" role="alert">{error}</p>{/if}

	{#if v}
		<div class="tiles">
			<div class="tile dark">
				<span class="k">Сейчас на сайте</span>
				<b class="num">{v.now.online}</b>
				<span class="s" title={onlineNames}>{onlineNames || 'никого'}</span>
			</div>
			<div class="tile">
				<span class="k">Ответ сервера</span>
				<b class="num">{ms(v.now.p50)}<small> мс</small></b>
				<span class="s num">p95 – {ms(v.now.p95)} мс · за 15 мин</span>
			</div>
			<div class="tile">
				<span class="k">Адрес сайта снаружи</span>
				{#if v.now.url}
					<b class="num"
						>{v.now.reachable === false
							? 'нет ответа'
							: `${ms(v.now.rtt)}`}{#if v.now.reachable !== false}<small> мс</small>{/if}</b
					>
					<span class="s" class:bad={v.now.reachable === false}
						>{v.now.reachable === false ? '✕ ' : v.now.reachable ? '✓ ' : ''}через туннель{v.now
							.probedAt
							? ` · ${fmtAgo(v.now.probedAt)}`
							: ''}</span
					>
				{:else}
					<b>–</b><span class="s">Доступ для группы не открыт</span>
				{/if}
			</div>
			<div class="tile">
				<span class="k">Доступность</span>
				<b class="num">{pct(range === 'day' ? v.uptimeDay : v.uptimeHour)}</b>
				<span class="s">за {range === 'day' ? 'сутки' : 'час'}</span>
			</div>
			<div class="tile">
				<span class="k">Запросов в минуту</span>
				<b class="num">{v.now.requestsPerMin.toLocaleString('ru-RU')}</b>
				<span class="s num" class:bad={v.now.errorsHour > 0}
					>{v.now.errorsHour
						? `ошибок сервера за час: ${v.now.errorsHour}`
						: 'без ошибок за час'}</span
				>
			</div>
			<div class="tile">
				<span class="k">Память Java</span>
				<b class="num">{heap}<small> %</small></b>
				<span class="s num">{fmtSize(v.memory.used)} из {fmtSize(v.memory.max)}</span>
			</div>
		</div>

		<section class="card pane">
			<h3 class="pane-title">Доступность</h3>
			<Uptime points={v.points} step={v.step} />
		</section>

		<section class="card pane">
			<h3 class="pane-title">Время ответа сервера, мс</h3>
			<LineChart
				points={pts}
				step={v.step}
				unit="мс"
				label="Время ответа сервера: медиана и 95-й перцентиль"
				series={[
					{ key: 'p50', label: 'медиана' },
					{ key: 'p95', label: 'p95', dashed: true }
				]}
			/>
		</section>

		{#if v.now.url}
			<section class="card pane">
				<h3 class="pane-title">Адрес сайта через туннель, мс</h3>
				<LineChart
					points={pts}
					step={v.step}
					unit="мс"
					label="Задержка ответа по адресу сайта снаружи"
					series={[{ key: 'rtt', label: 'задержка' }]}
				/>
			</section>
		{/if}

		<section class="card pane">
			<h3 class="pane-title">На сайте</h3>
			<LineChart
				points={pts}
				step={v.step}
				label="Сколько людей было на сайте"
				series={[{ key: 'online', label: 'человек' }]}
				area
			/>
		</section>

		<section class="card pane">
			<h3 class="pane-title">Запросов</h3>
			<LineChart
				points={pts}
				step={v.step}
				label="Запросов к серверу за отрезок"
				series={[{ key: 'requests', label: 'запросов' }]}
				area
			/>
		</section>
	{/if}
</div>

<style>
	.monitor {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
	}
	.head {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--s3);
	}
	.head p {
		margin: 0;
	}
	.seg {
		display: inline-flex;
		padding: 3px;
		border-radius: var(--r-full);
		background: var(--surface-2);
	}
	.seg button {
		height: 30px;
		padding: 0 14px;
		border: 0;
		border-radius: var(--r-full);
		background: none;
		color: var(--text-2);
		font: inherit;
		font-size: 13px;
		font-weight: 600;
	}
	.seg button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);
	}
	.tiles {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
		gap: var(--s3);
	}
	.tile {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
		padding: 14px 16px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
	}
	.tile.dark {
		border-color: transparent;
		background: var(--text);
		color: var(--surface);
	}
	.tile.dark .k,
	.tile.dark .s {
		color: color-mix(in srgb, var(--surface) 70%, transparent);
	}
	.k {
		color: var(--text-3);
		font-size: 12.5px;
		font-weight: 600;
	}
	.tile b {
		font-size: 26px;
		font-weight: 750;
		letter-spacing: -0.02em;
	}
	.tile b small {
		font-size: 14px;
		font-weight: 600;
	}
	.s {
		overflow: hidden;
		color: var(--text-3);
		font-size: 12.5px;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.s.bad {
		color: var(--danger);
	}
</style>
