<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { Copy, KeyRound } from '@lucide/svelte';
	import { request } from '$lib/api';
	import { copy } from '$lib/copy';
	import { fmtSize } from '$lib/format';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { TransferCode } from './types';

	// Код переноса: на новом компьютере вводят адрес сайта и этот код — сайт переезжает туда целиком,
	// а здесь останавливается. Код одноразовый и живёт 15 минут.
	let v = $state<TransferCode | null>(null);
	let busy = $state(false);
	let now = $state(Date.now());
	let timer: ReturnType<typeof setInterval> | undefined;

	async function load() {
		try {
			const next = await request<TransferCode | null>('/api/host/transfer/code');
			// Код подтвердили, и сайт отсюда уехал — дальше здесь страница ожидания.
			if (!next && v?.phase === 'sent') location.replace('/standby');
			v = next;
		} catch {
			/* нет прав или сайт уже не здесь — оставим как есть */
		}
		now = Date.now();
	}

	onMount(() => {
		load();
		timer = setInterval(() => {
			now = Date.now();
			if (v) load();
		}, 2000);
	});
	onDestroy(() => clearInterval(timer));

	async function create() {
		busy = true;
		try {
			v = await request<TransferCode>('/api/host/transfer/code', { method: 'POST', body: {} });
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	async function cancel() {
		try {
			await request('/api/host/transfer/code', { method: 'DELETE' });
			v = null;
		} catch (e) {
			toastError(e);
		}
	}

	const short = (u: string) => u.replace(/^https?:\/\//, '');
	const left = $derived(v ? Math.max(0, Math.ceil((v.expiresAt - now) / 60_000)) : 0);
</script>

{#if !v}
	<p class="small muted">
		<strong>По коду</strong> — проще всего: на новом компьютере установите groupbase и при первом запуске
		нажмите «Перенести по коду». Сайт переедет туда со всеми данными и тем же адресом, а здесь остановится.
		Код можно взять и с телефона.
	</p>
	<div>
		<Button variant="primary" loading={busy} onclick={create}
			><KeyRound size={16} /> Получить код переноса</Button
		>
	</div>
{:else}
	<section class="card code-card" aria-live="polite">
		<div class="line">
			<span class="label">Адрес сайта</span>
			{#if v.url}
				<span class="val">
					<strong>{short(v.url)}</strong>
					<Button
						size="s"
						variant="ghost"
						label="Скопировать адрес"
						onclick={() => copy(short(v!.url!))}><Copy size={15} /></Button
					>
				</span>
			{:else}
				<span class="small bad"
					>Доступ для группы не открыт — откройте его выше: через него новый компьютер заберёт
					данные. Если оба компьютера дома в одной сети — «Только локальная сеть».</span
				>
			{/if}
		</div>
		<div class="line">
			<span class="label">Код</span>
			<span class="val">
				<strong class="code num">{v.code}</strong>
				<Button size="s" variant="ghost" label="Скопировать код" onclick={() => copy(v!.code)}
					><Copy size={15} /></Button
				>
			</span>
		</div>
		<p class="small muted state">
			{#if v.phase === 'sending'}
				<span class="spinner" aria-hidden="true"></span> Другой компьютер забирает данные: {fmtSize(
					v.sent
				)}. Пока идёт перенос, изменения на сайте не принимаются.
			{:else if v.phase === 'sent'}
				<span class="spinner" aria-hidden="true"></span> Данные у него — ждём подтверждения, потом сайт
				здесь остановится.
			{:else}
				Действует ещё {left} мин, один раз. Введите адрес и код на новом компьютере.
			{/if}
		</p>
		<div>
			<Button size="s" variant="ghost" onclick={cancel}>Отменить код</Button>
		</div>
	</section>
{/if}

<style>
	.code-card {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.line {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.line .label {
		margin: 0;
	}
	.val {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.val strong {
		overflow-wrap: anywhere;
	}
	.code {
		font-size: 26px;
		letter-spacing: 0.08em;
	}
	.state {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
	}
	.bad {
		color: var(--danger);
	}
	.spinner {
		flex: none;
		width: 16px;
		height: 16px;
		border: 2px solid var(--border);
		border-top-color: var(--text);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
</style>
