<script lang="ts">
	import { onDestroy } from 'svelte';
	import { request } from '$lib/api';
	import { fmtSize } from '$lib/format';
	import { waitForRestart } from '$lib/settings/server/restart';
	import type { PullProgress } from '$lib/settings/server/types';
	import Button from '$lib/ui/Button.svelte';

	// Забрать сайт сюда с работающего компьютера по адресу и коду переноса — вместо файла копии.
	// endpoint: на первом запуске — /api/setup/transfer?code=…, на странице ожидания — /api/host/pull.
	// busy — наружу: пока идёт перенос, страница прячет «создать новую группу» и прочее.
	let {
		endpoint,
		label = 'Перенести сюда',
		busy = $bindable(false)
	}: { endpoint: string; label?: string; busy?: boolean } = $props();

	let url = $state('');
	let code = $state('');
	let error = $state('');
	let progress = $state<PullProgress | null>(null);
	let done = $state('');
	let timer: ReturnType<typeof setInterval> | undefined;
	onDestroy(() => clearInterval(timer));

	async function poll() {
		try {
			progress = await request<PullProgress>('/api/host/pull', { quiet401: true });
		} catch {
			/* сервер занят переносом — спросим ещё */
		}
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		busy = true;
		progress = null;
		timer = setInterval(poll, 1000);
		try {
			const r = await request<{ status: string; message: string }>(endpoint, {
				method: 'POST',
				body: { url: url.trim(), code: code.trim() },
				anonymous: true
			});
			clearInterval(timer);
			done = r.message;
			await waitForRestart();
		} catch (err) {
			clearInterval(timer);
			error = err instanceof Error ? err.message : 'Не получилось';
			busy = false;
		}
	}

	const step = $derived.by(() => {
		const p = progress;
		if (!p || p.phase === 'idle' || p.phase === 'download') {
			const got = p?.received ?? 0;
			return got
				? `Забираем данные: ${fmtSize(got)}${p?.total ? ` из ~${fmtSize(p.total)}` : ''}`
				: 'Связываемся с сайтом…';
		}
		if (p.phase === 'check') return 'Проверяем копию…';
		if (p.phase === 'confirm') return 'Останавливаем сайт на прежнем компьютере…';
		return 'Перезапускаемся с данными…';
	});
	const percent = $derived(
		progress?.total ? Math.min(100, Math.round((progress.received / progress.total) * 100)) : 0
	);
</script>

{#if done}
	<div class="state" role="status">
		<span class="spinner" aria-hidden="true"></span>
		<p>{done}</p>
	</div>
{:else}
	<form class="pull" onsubmit={submit}>
		<div>
			<label class="label" for="pull-url">Адрес сайта</label>
			<input
				id="pull-url"
				class="input"
				bind:value={url}
				placeholder="groupbase.cloudpub.ru"
				autocomplete="off"
				autocapitalize="none"
				spellcheck="false"
				inputmode="url"
				required
				disabled={busy}
			/>
		</div>
		<div>
			<label class="label" for="pull-code">Код переноса</label>
			<input
				id="pull-code"
				class="input num code"
				bind:value={code}
				placeholder="XXXX-XXXX-XXXX"
				autocomplete="one-time-code"
				autocapitalize="characters"
				spellcheck="false"
				required
				disabled={busy}
			/>
			<p class="hint">
				Код — на компьютере, где сайт работает сейчас: «Настройки → Сервер → Перенос на другой
				компьютер». Можно взять и с телефона, если вы администратор.
			</p>
		</div>
		{#if busy}
			<div
				class="progress"
				role="progressbar"
				aria-valuemin={0}
				aria-valuemax={100}
				aria-valuenow={percent}
			>
				<span class:indeterminate={!percent} style:width={percent ? `${percent}%` : null}></span>
			</div>
			<p class="faint small num">{step}</p>
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		<p class="faint small">
			Сайт переедет сюда со всеми данными и тем же адресом, а на прежнем компьютере остановится.
		</p>
		<Button variant="primary" type="submit" loading={busy}>{label}</Button>
	</form>
{/if}

<style>
	.pull {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		text-align: left;
	}
	.pull p {
		margin: 0;
	}
	.code {
		text-transform: uppercase;
		letter-spacing: 0.08em;
	}
	.progress {
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
	.progress .indeterminate {
		width: 30%;
		animation: slide 1.2s ease-in-out infinite;
	}
	@keyframes slide {
		from {
			transform: translateX(-100%);
		}
		to {
			transform: translateX(340%);
		}
	}
	.state {
		display: flex;
		align-items: center;
		gap: var(--s3);
		padding: var(--s4);
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.state p {
		margin: 0;
	}
	.spinner {
		flex: none;
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
		.progress .indeterminate,
		.spinner {
			animation: none;
		}
	}
</style>
