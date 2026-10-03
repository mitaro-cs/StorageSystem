<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { post } from '$lib/api';
	import { deviceLabel } from '$lib/push';
	import Button from '$lib/ui/Button.svelte';

	// QR-код входа, отсканированный камерой телефона (0.7): код из адреса → сессия → на главную.
	let error = $state('');

	onMount(async () => {
		try {
			await post(
				'/api/auth/link/redeem',
				{ code: page.params.code, device: deviceLabel() },
				{ anonymous: true }
			);
			location.replace('/');
		} catch (e) {
			error = e instanceof Error ? e.message : 'Код не подошёл';
		}
	});
</script>

<svelte:head><title>Вход по коду · groupbase</title></svelte:head>

<h1>Вход по коду</h1>
{#if error}
	<p class="error-text" role="alert">{error}</p>
	<Button variant="primary" href="/login">На страницу входа</Button>
{:else}
	<p class="muted">Входим…</p>
{/if}
