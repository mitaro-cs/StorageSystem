<script lang="ts">
	import { page } from '$app/state';
	import { fly } from '$lib/motion';
	import Sidebar from '$lib/shell/Sidebar.svelte';
	import BottomNav from '$lib/shell/BottomNav.svelte';
	import MobileBar from '$lib/shell/MobileBar.svelte';
	import { palette } from '$lib/shell/palette.svelte';
	import Hotkeys from '$lib/shell/Hotkeys.svelte';
	import SwipeBack from '$lib/shell/SwipeBack.svelte';
	import { viewer } from '$lib/files/viewer.svelte';
	import { initPwa, pwa } from '$lib/pwa.svelte';
	import { startBell } from '$lib/notify.svelte';
	import { ApiError, request } from '$lib/api';
	import { toast } from '$lib/toasts.svelte';
	import { flushOutbox, initOffline, offline, syncNow } from '$lib/offline/engine';
	import { rememberAccount } from '$lib/accounts';
	import { session } from '$lib/session.svelte';
	import { onMount } from 'svelte';
	import { slide } from '$lib/motion';
	import { CloudOff, CloudUpload, WifiOff } from '@lucide/svelte';

	let { children } = $props();
	// На страницах деталей верхнюю панель заменяет «← Раздел» (BackBar), как на макете.
	const detail = $derived(
		/^\/((homework|news|subjects|materials)\/[^/]+|install$)/.test(page.url.pathname)
	);
	let collapsed = $state(false);
	onMount(initPwa);
	onMount(startBell);
	onMount(() => {
		let told = false;
		const onRejection = (e: PromiseRejectionEvent) => {
			if (e.reason instanceof ApiError && e.reason.code === 'offline') {
				e.preventDefault();
				if (!told) toast(e.reason.message, 'info');
				told = true;
				setTimeout(() => (told = false), 10_000);
			}
		};
		addEventListener('unhandledrejection', onRejection);
		return () => removeEventListener('unhandledrejection', onRejection);
	});
	onMount(() => {
		if (session.me) {
			initOffline(session.me);
			const u = session.me.user;
			rememberAccount({
				userId: u.id,
				username: u.username,
				displayName: u.displayName,
				avatar: u.avatar
			});
		}
	});

	// Сервер группы недоступен, а интернет есть (выключен компьютер хоста): раз в 30 секунд
	// проверяем, не вернулся ли он, — и сразу отправляем сделанное без него.
	$effect(() => {
		if (!pwa.offline || !pwa.network) return;
		const id = setInterval(async () => {
			try {
				await request('/api/health');
				await flushOutbox();
				await syncNow();
			} catch {
				/* всё ещё недоступен */
			}
		}, 30_000);
		return () => clearInterval(id);
	});

	// Палитра грузится при первом открытии — её код не нужен для первого экрана.
	let paletteWanted = $state(false);
	$effect(() => {
		if (palette.open) paletteWanted = true;
	});
</script>

<a class="skip" href="#content">Перейти к содержимому</a>
<div class="shell" class:collapsed>
	<div class="desktop-only"><Sidebar bind:collapsed /></div>
	<div class="main-col">
		{#if !detail}<div class="mobile-only"><MobileBar /></div>{/if}
		{#if pwa.offline && pwa.network}
			<div class="offline" role="status" transition:slide>
				<CloudOff size={15} /> Сервер группы выключен — показаны сохранённые данные{offline.pending
					? ` · отправится, когда он включится: ${offline.pending}`
					: ''}
			</div>
		{:else if pwa.offline}
			<div class="offline" role="status" transition:slide>
				<WifiOff size={15} /> Нет сети — показаны сохранённые данные{offline.pending
					? ` · отправится позже: ${offline.pending}`
					: ''}
			</div>
		{:else if offline.pending}
			<div class="offline sending" role="status" transition:slide>
				<CloudUpload size={15} /> Отправляем сделанное без сети: {offline.pending}
			</div>
		{/if}
		<main id="content" tabindex="-1">
			{#key page.url.pathname}
				<div class="page" in:fly={{ y: 6, duration: 180 }}>
					{@render children()}
				</div>
			{/key}
		</main>
	</div>
	<div class="mobile-only"><BottomNav /></div>
</div>
{#if paletteWanted}
	{#await import('$lib/shell/CommandPalette.svelte') then m}<m.default />{/await}
{/if}
<Hotkeys />
<SwipeBack />
<!-- Просмотр файлов: код грузится при первом открытии файла. -->
{#if viewer.open}
	{#await import('$lib/files/FileViewer.svelte') then m}<m.default />{/await}
{/if}

<style>
	.shell {
		display: flex;
		min-height: 100dvh;
	}
	.main-col {
		flex: 1;
		min-width: 0;
	}
	main {
		max-width: calc(var(--content) + 2 * var(--s5));
		margin: 0 auto;
		padding: var(--s4) var(--s4)
			calc(var(--bottom-nav) + var(--bottom-gap) + var(--s7) + env(safe-area-inset-bottom));
		outline: none;
	}
	.desktop-only {
		display: none;
	}
	@media (min-width: 900px) {
		.desktop-only {
			display: block;
		}
		.mobile-only {
			display: none;
		}
		main {
			padding: var(--s6) var(--s5) var(--s7);
		}
	}
	.offline {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		padding: 8px 16px;
		background: var(--amber-soft);
		color: var(--amber);
		font-size: 13.5px;
		font-weight: 550;
	}
	.offline.sending {
		background: var(--surface-2);
		color: var(--text-2);
	}
	.skip {
		position: absolute;
		left: -9999px;
		top: 8px;
		z-index: 200;
		padding: 8px 12px;
		background: var(--surface);
		border-radius: 8px;
		box-shadow: var(--shadow-2);
	}
	.skip:focus {
		left: 8px;
	}
</style>
