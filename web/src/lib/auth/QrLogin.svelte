<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { RefreshCw } from '@lucide/svelte';
	import { ApiError, post } from '$lib/api';
	import { deviceLabel } from '$lib/push';
	import QrCode from '$lib/ui/QrCode.svelte';
	import Button from '$lib/ui/Button.svelte';

	// Новое устройство показывает QR; телефон, где человек уже вошёл, сканирует и подтверждает.
	let { onsuccess }: { onsuccess: () => void } = $props();

	let code = $state('');
	let poll = '';
	let expiresAt = $state(0);
	let now = $state(Date.now());
	let stale = $state(false);
	let error = $state('');
	let renewals = 0;
	let pollTimer: ReturnType<typeof setTimeout> | undefined;
	let clock: ReturnType<typeof setInterval> | undefined;

	async function start() {
		stale = false;
		error = '';
		try {
			const r = await post<{ code: string; poll: string; expiresAt: number }>(
				'/api/auth/qr',
				{ device: deviceLabel() },
				{ anonymous: true }
			);
			code = r.code;
			poll = r.poll;
			expiresAt = r.expiresAt;
			schedule();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Не удалось получить код';
		}
	}

	function schedule() {
		clearTimeout(pollTimer);
		pollTimer = setTimeout(check, 2000);
	}

	async function check() {
		try {
			const r = await post<{ status: string }>('/api/auth/qr/poll', { poll }, { anonymous: true });
			if (r.status === 'ok') return onsuccess();
			schedule();
		} catch (e) {
			// Код истёк — показываем новый, но не бесконечно: вдруг вкладку забыли открытой.
			if (e instanceof ApiError && e.status === 410 && renewals++ < 5) return start();
			if (e instanceof ApiError && e.status === 410) stale = true;
			else schedule();
		}
	}

	onMount(() => {
		start();
		clock = setInterval(() => (now = Date.now()), 1000);
	});
	onDestroy(() => {
		clearTimeout(pollTimer);
		clearInterval(clock);
	});

	const left = $derived(Math.max(0, Math.round((expiresAt - now) / 1000)));
</script>

<div class="qr-login">
	{#if error}
		<p class="error-text" role="alert">{error}</p>
		<Button onclick={start}>Попробовать ещё раз</Button>
	{:else if stale}
		<p class="muted">Код устарел.</p>
		<Button onclick={() => ((renewals = 0), start())}><RefreshCw size={16} /> Показать новый</Button
		>
	{:else if code}
		<div class="code" data-code={code}>
			<QrCode value="{location.origin}/link/{code}" label="QR-код для входа" />
		</div>
		<ol class="how">
			<li>Откройте groupbase на телефоне, где вы уже вошли</li>
			<li>Профиль → «Сканировать QR-код» или просто камера телефона</li>
			<li>Нажмите «Разрешить вход» — здесь всё откроется само</li>
		</ol>
		<p class="faint small num">Код обновится через {left} с</p>
	{:else}
		<div class="code placeholder" aria-busy="true"></div>
	{/if}
</div>

<style>
	.qr-login {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s3);
		text-align: center;
	}
	.code {
		width: 220px;
	}
	.placeholder {
		aspect-ratio: 1;
		border-radius: 12px;
		background: var(--surface-2);
	}
	.how {
		margin: 0;
		padding-left: 20px;
		text-align: left;
		color: var(--text-2);
		font-size: 14.5px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
</style>
