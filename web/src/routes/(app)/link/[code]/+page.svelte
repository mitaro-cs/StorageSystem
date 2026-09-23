<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { CircleCheck, MonitorSmartphone } from '@lucide/svelte';
	import { get, post } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';

	// Подтверждение входа на другом устройстве (оно показывает QR-код).
	let info = $state<{ device: string; createdAt: number } | null>(null);
	let problem = $state('');
	let done = $state(false);
	let busy = $state(false);
	const code = $derived(page.params.code ?? '');

	onMount(async () => {
		try {
			info = await get(`/api/auth/qr/${code}`);
		} catch (e) {
			problem = e instanceof Error ? e.message : 'Код не подошёл';
		}
	});

	async function approve() {
		busy = true;
		try {
			await post(`/api/auth/qr/${code}/approve`);
			done = true;
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>Вход на другом устройстве · groupbase</title></svelte:head>

{#if problem}
	<div class="card"><Empty title="Код не подошёл" text={problem} /></div>
{:else if !info}
	<Skeleton lines={3} />
{:else if done}
	<div class="card box ok">
		<CircleCheck size={44} />
		<h1>Готово</h1>
		<p class="muted">На другом устройстве открылся groupbase.</p>
		<Button onclick={() => goto('/')}>На главную</Button>
	</div>
{:else}
	<div class="card box">
		<span class="icon"><MonitorSmartphone size={30} /></span>
		<h1>Войти на другом устройстве?</h1>
		<dl class="kv">
			<div>
				<dt>Устройство</dt>
				<dd>{info.device || 'неизвестно'}</dd>
			</div>
			<div>
				<dt>Запрошено</dt>
				<dd class="num">{fmtAgo(info.createdAt)}</dd>
			</div>
		</dl>
		<p class="tip amber">
			<span
				>Разрешайте, только если QR-код показан <strong>на вашем устройстве</strong> и вы сами его отсканировали.
				Если код прислал кто-то другой — нажмите «Отмена».</span
			>
		</p>
		<div class="row wrap actions">
			<Button variant="primary" onclick={approve} loading={busy}>Разрешить вход</Button>
			<Button variant="ghost" onclick={() => goto('/')}>Отмена</Button>
		</div>
	</div>
{/if}

<style>
	.box {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		padding: var(--s5);
		max-width: 520px;
		margin: var(--s5) auto 0;
	}
	.box h1 {
		font-size: 24px;
	}
	.icon {
		display: grid;
		place-items: center;
		width: 60px;
		height: 60px;
		border-radius: 18px;
		background: var(--surface-2);
	}
	.ok {
		align-items: center;
		text-align: center;
		color: var(--ok);
	}
	.ok h1 {
		color: var(--text);
	}
	.actions {
		gap: 8px;
	}
</style>
