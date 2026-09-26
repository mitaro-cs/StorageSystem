<script lang="ts">
	import { onMount } from 'svelte';
	import { ArrowLeftRight, CloudDownload, Monitor } from '@lucide/svelte';
	import { ApiError, request } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import { waitForRestart } from '$lib/settings/server/restart';
	import type { Hosts } from '$lib/settings/server/types';
	import Button from '$lib/ui/Button.svelte';

	// Этот компьютер хоста сейчас не хост: сайт работает на другом (или ждёт данных из облака).
	// Страница видна только в окне приложения здесь — участники ходят на настоящий хост.
	let h = $state<Hosts | null>(null);
	let denied = $state(false);
	let busy = $state(false);
	let error = $state('');
	let restarting = false;

	async function load() {
		try {
			h = await request<Hosts>('/api/host', { quiet401: true });
			denied = false;
			if (h.role === 'host' || h.role === 'off') {
				// Сайт снова работает здесь.
				location.replace('/');
			} else if (h.role === 'switching' && !restarting) {
				restarting = true;
				await waitForRestart();
			}
		} catch (e) {
			if (e instanceof ApiError && (e.status === 401 || e.status === 403)) denied = true;
		}
	}

	onMount(() => {
		load();
		const t = setInterval(load, 3000);
		return () => clearInterval(t);
	});

	async function takeOver() {
		error = '';
		busy = true;
		try {
			h = await request<Hosts>('/api/host/takeover', {
				method: 'POST',
				body: {},
				quiet401: true
			});
			if (h.role === 'switching') {
				restarting = true;
				await waitForRestart();
			}
		} catch (e) {
			error = e instanceof Error ? e.message : 'Не получилось';
		} finally {
			busy = false;
		}
	}

	const other = $derived(h?.other?.name ?? 'другом компьютере');
	const spinning = $derived(
		!!h && (h.role === 'waiting' || h.role === 'switching' || h.role === 'checking')
	);
	const title = $derived.by(() => {
		if (!h) return 'Сайт работает на другом компьютере';
		if (h.role === 'switching') return 'Переносим сайт сюда…';
		if (h.role === 'checking') return 'Проверяем, где сейчас сайт…';
		if (h.role === 'waiting')
			return h.total ? 'Облако докачивает данные' : `Ждём ответа от «${other}»…`;
		switch (h.plan) {
			case 'live':
				return `Сайт работает на «${other}»`;
			case 'silent':
				return h.other ? `«${other}» не на связи` : 'Сайт запускали на другом компьютере';
			case 'handed':
				return 'Сайт передан на другой компьютер';
			case 'detached':
				return `На «${other}» выключили перенос`;
			default:
				return 'Сайт сейчас не здесь';
		}
	});
	const actionLabel = $derived(
		h?.action === 'request'
			? 'Перенести сюда'
			: h?.action === 'back'
				? 'Вернуть сайт сюда'
				: 'Запустить здесь'
	);
</script>

<svelte:head><title>Сайт на другом компьютере · groupbase</title></svelte:head>

<div class="standby">
	<div class="pair" aria-hidden="true">
		<span class="pc" class:on={h?.plan === 'live' || h?.role === 'waiting'}>
			<i><Monitor size={26} /></i>
			<small>{h?.other?.name ?? 'другой'}</small>
		</span>
		<span class="flow" class:moving={spinning}><ArrowLeftRight size={20} /></span>
		<span class="pc" class:on={h?.role === 'switching'}>
			<i><Monitor size={26} /></i>
			<small>этот</small>
		</span>
	</div>

	<h1>{title}</h1>

	{#if denied}
		<p class="muted">
			Сайт группы сейчас работает на другом компьютере. Откройте его по обычной ссылке — там всё
			свежее.
		</p>
	{:else if h}
		<!-- Для «работает на другом» заголовок уже всё сказал. -->
		{#if h.message && !(h.role === 'standby' && h.plan === 'live')}
			<p class="muted">{h.message}</p>
		{/if}

		{#if h.role === 'waiting' && h.total}
			<div
				class="progress"
				role="progressbar"
				aria-valuemin={0}
				aria-valuemax={h.total}
				aria-valuenow={h.have ?? 0}
			>
				<span style:width="{Math.round(((h.have ?? 0) / h.total) * 100)}%"></span>
			</div>
			<p class="faint small num">
				<CloudDownload size={14} /> Файлов на месте: {h.have ?? 0} из {h.total}
			</p>
		{/if}

		{#if h.snapshotAt}
			<p class="faint small">
				Последние данные сохранены {fmtAgo(h.snapshotAt)}{h.snapshotBy
					? ` на «${h.snapshotBy}»`
					: ''}.
			</p>
		{/if}

		{#if spinning}
			<span class="spinner" aria-hidden="true"></span>
		{/if}

		{#if h.action}
			<div class="act">
				<Button variant="primary" loading={busy} onclick={takeOver}>{actionLabel}</Button>
				{#if h.action === 'request'}
					<p class="faint small">
						«{other}» сохранит последние изменения и остановится, а сайт продолжит работу здесь —
						обычно через минуту, пока облачный диск доставит файлы.
					</p>
				{:else if h.action === 'start'}
					<p class="tip amber small">
						<span
							>Если «{other}» на самом деле включён, сначала закройте groupbase на нём: иначе
							изменения, сделанные там после последнего сохранения, сюда не попадут.</span
						>
					</p>
				{/if}
			</div>
		{/if}

		{#if error || h.error}<p class="error-text" role="alert">{error || h.error}</p>{/if}

		<p class="faint small foot">
			Этот компьютер — «{h.computer?.name ?? 'этот компьютер'}». Здесь данные могут быть
			устаревшими, поэтому сайт отсюда не открывается, пока он работает на другом компьютере.
		</p>
	{/if}
</div>

<style>
	.standby {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
		text-align: center;
	}
	h1 {
		margin: 4px 0 0;
		font-size: 24px;
		letter-spacing: -0.02em;
	}
	p {
		margin: 0;
		max-width: 42ch;
	}
	.pair {
		display: flex;
		align-items: center;
		gap: 14px;
		margin-bottom: 4px;
	}
	.pc {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 6px;
		width: 84px;
	}
	.pc i {
		display: grid;
		place-items: center;
		width: 60px;
		height: 60px;
		border-radius: 20px;
		background: var(--surface-2);
		color: var(--text-3);
		font-style: normal;
		transition:
			background-color 400ms var(--ease),
			color 400ms var(--ease);
	}
	.pc.on i {
		background: var(--accent);
		color: var(--accent-text);
	}
	.pc small {
		max-width: 100%;
		overflow: hidden;
		color: var(--text-3);
		font-size: 12px;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.flow {
		margin-bottom: 20px;
		color: var(--text-3);
	}
	.flow.moving {
		animation: flow 1.2s ease-in-out infinite;
	}
	@keyframes flow {
		50% {
			transform: translateX(4px);
			color: var(--text);
		}
	}
	.progress {
		width: min(320px, 100%);
		height: 8px;
		border-radius: 4px;
		background: var(--surface-3);
		overflow: hidden;
	}
	.progress span {
		display: block;
		height: 100%;
		background: var(--accent);
		transition: width 400ms var(--ease);
	}
	.act {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		margin-top: 6px;
	}
	.act .tip {
		text-align: left;
	}
	.foot {
		margin-top: var(--s3);
		padding-top: var(--s3);
		border-top: 1px solid var(--border);
	}
	.spinner {
		width: 22px;
		height: 22px;
		border: 3px solid var(--border);
		border-top-color: var(--text);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.flow.moving,
		.spinner {
			animation: none;
		}
	}
</style>
