<script lang="ts">
	import { onDestroy } from 'svelte';
	import { CircleCheck, RefreshCw } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { absolute } from '$lib/copy';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';

	// «Показать код» (0.7): QR и 6 цифр для входа на другом устройстве. Новое устройство сканирует
	// QR камерой (откроется /enter/…) или вводит цифры на странице входа. Код – на 3 минуты, когда
	// устарел – показываем новый (не больше 5 раз), вошли – пишем, где.
	let { open = $bindable(false) }: { open?: boolean } = $props();

	let code = $state('');
	let pin = $state('');
	let expiresAt = $state(0);
	let now = $state(Date.now());
	let done = $state('');
	let error = $state('');
	let renewals = 0;
	let timer: ReturnType<typeof setInterval> | undefined;

	async function issue() {
		error = '';
		try {
			const r = await post<{ code: string; pin: string; expiresAt: number }>('/api/auth/link');
			code = r.code;
			pin = r.pin;
			expiresAt = r.expiresAt;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Не удалось получить код';
		}
	}

	async function check() {
		now = Date.now();
		if (!code || done) return;
		try {
			const r = await post<{ status: string; device?: string }>('/api/auth/link/status', { code });
			if (r.status === 'used') done = r.device ?? 'другое устройство';
			else if (r.status === 'expired' || expiresAt < now) {
				code = '';
				if (renewals++ < 5) issue();
			}
		} catch {
			/* нет сети – попробуем через пару секунд */
		}
	}

	$effect(() => {
		if (open) {
			done = '';
			renewals = 0;
			issue();
			timer = setInterval(check, 2000);
		} else clearInterval(timer);
	});
	onDestroy(() => clearInterval(timer));

	const left = $derived(Math.max(0, Math.round((expiresAt - now) / 1000)));
</script>

<Modal bind:open title="Вход на другом устройстве">
	{#if done}
		<div class="done" role="status">
			<CircleCheck size={44} />
			<p><strong>Готово!</strong> Вошли: {done}.</p>
		</div>
	{:else if error}
		<p class="error-text" role="alert">{error}</p>
		<Button onclick={issue}><RefreshCw size={16} /> Попробовать ещё раз</Button>
	{:else if code}
		<div class="show">
			<div class="qr" data-code={code}>
				<QrCode value={absolute(`/enter/${code}`)} label="QR-код для входа" />
			</div>
			<p class="pin num" aria-label="Код {pin.split('').join(' ')}">
				{pin.slice(0, 3)}&nbsp;{pin.slice(3)}
			</p>
			<p class="muted small">
				На новом устройстве откройте Campus → «Вход» → «По коду» и отсканируйте QR (или камерой
				телефона) либо введите цифры. Код обновится через <span class="num">{left}</span> с.
			</p>
		</div>
	{:else}
		<div class="qr placeholder" aria-busy="true"></div>
	{/if}
	{#snippet footer()}
		<Button onclick={() => (open = false)}>{done ? 'Закрыть' : 'Отмена'}</Button>
	{/snippet}
</Modal>

<style>
	.show {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s3);
		text-align: center;
	}
	.qr {
		width: 220px;
	}
	.placeholder {
		aspect-ratio: 1;
		margin: 0 auto;
		border-radius: 12px;
		background: var(--surface-2);
	}
	.pin {
		margin: 0;
		font-size: 34px;
		font-weight: 750;
		letter-spacing: 0.08em;
	}
	.done {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s2);
		padding: var(--s4);
		color: var(--ok);
		text-align: center;
	}
	.done p {
		color: var(--text);
		margin: 0;
	}
</style>
