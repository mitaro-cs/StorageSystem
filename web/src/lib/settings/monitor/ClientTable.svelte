<script lang="ts">
	import { fmtAgo } from '$lib/format';
	import type { ClientRow } from './types';

	// Скорость у группы (1.0.2): как быстро сайт отвечает людям – по устройству, сети и пути.
	// Полоса – от медианы до 95-го перцентиля ответа API, на общей шкале всех строк.
	let { rows }: { rows: ClientRow[] } = $props();

	const DEVICE = { phone: 'Телефон', tablet: 'Планшет', computer: 'Компьютер' };
	const NET = {
		wifi: 'Wi-Fi',
		cellular: 'Мобильная сеть',
		ethernet: 'Кабель',
		other: 'Другая сеть',
		unknown: 'Сеть не известна'
	};
	const VIA = { tunnel: 'через туннель', local: 'в локальной сети' };

	const scale = $derived(Math.max(100, ...rows.map((r) => r.apiP95 ?? 0)));
	const pct = (ms: number | null) => `${((ms ?? 0) / scale) * 100}%`;
	const ms = (x: number | null) =>
		x === null ? '–' : x >= 1000 ? `${(x / 1000).toLocaleString('ru-RU')} с` : `${x} мс`;
	/** Медленно – больше секунды на ответ у половины запросов. */
	const tone = (r: ClientRow) =>
		(r.apiP50 ?? 0) > 1000 ? 'bad' : (r.apiP50 ?? 0) > 320 ? 'warn' : 'good';
</script>

{#if rows.length === 0}
	<p class="muted small empty">
		Замеров пока нет: браузеры участников присылают их сами раз в три минуты, пока сайт открыт.
	</p>
{:else}
	<div class="table" role="table" aria-label="Скорость у группы за сутки">
		<div class="row head" role="row">
			<span role="columnheader">Устройство и сеть</span>
			<span role="columnheader">Ответ сервера (медиана – p95)</span>
			<span role="columnheader" class="r">Страница</span>
		</div>
		{#each rows as r (`${r.device}-${r.net}-${r.via}`)}
			<div class="row" role="row">
				<span role="cell" class="who">
					<strong>{DEVICE[r.device]} · {NET[r.net]}</strong>
					<span class="faint small">{VIA[r.via]} · {r.people} чел. · {fmtAgo(r.lastAt)}</span>
				</span>
				<span role="cell" class="lat">
					<span class="track" aria-hidden="true">
						<span
							class="range {tone(r)}"
							style:left={pct(r.apiP50)}
							style:width="calc({pct(r.apiP95)} - {pct(r.apiP50)})"
						></span>
						<span class="dot {tone(r)}" style:left={pct(r.apiP50)}></span>
					</span>
					<span class="num small">{ms(r.apiP50)} – {ms(r.apiP95)}</span>
				</span>
				<span role="cell" class="num r">{ms(r.loadP50)}</span>
			</div>
		{/each}
	</div>
	<p class="faint small note">
		За сутки. Сеть различает только Chrome на Android – у iPhone и компьютеров она «не известна».
		Медленно, если половина ответов дольше секунды.
	</p>
{/if}

<style>
	.empty {
		margin: 0;
	}
	.table {
		display: flex;
		flex-direction: column;
	}
	.row {
		display: grid;
		grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) 70px;
		align-items: center;
		gap: 12px;
		padding: 10px 0;
		border-top: 1px solid var(--border);
	}
	.row.head {
		border-top: 0;
		padding-top: 0;
		color: var(--text-3);
		font-size: 12px;
		font-weight: 600;
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.who strong {
		overflow: hidden;
		font-size: 14px;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.lat {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.track {
		position: relative;
		height: 8px;
		border-radius: 4px;
		background: var(--surface-2);
	}
	.range {
		position: absolute;
		top: 0;
		bottom: 0;
		min-width: 2px;
		border-radius: 4px;
		opacity: 0.35;
	}
	.dot {
		position: absolute;
		top: 50%;
		width: 10px;
		height: 10px;
		border: 2px solid var(--surface);
		border-radius: 50%;
		transform: translate(-50%, -50%);
	}
	.good {
		background: var(--ok);
	}
	.warn {
		background: var(--amber);
	}
	.bad {
		background: var(--danger);
	}
	.r {
		text-align: right;
	}
	.note {
		margin: 10px 0 0;
	}
	@media (max-width: 520px) {
		.row {
			grid-template-columns: minmax(0, 1fr) 64px;
		}
		.row .lat {
			grid-column: 1 / -1;
			grid-row: 2;
		}
		.row.head span:nth-child(2) {
			display: none;
		}
	}
</style>
