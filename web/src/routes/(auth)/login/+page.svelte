<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { ApiError, get, post } from '$lib/api';
	import Button from '$lib/ui/Button.svelte';

	let username = $state('');
	let password = $state('');
	let code = $state('');
	let ticket = $state<string | null>(null);
	let error = $state('');
	let busy = $state(false);

	const next = $derived.by(() => {
		const n = page.url.searchParams.get('next');
		return n && /^\/(?![/\\])/.test(n) ? n : '/';
	});

	onMount(async () => {
		const s = await get<{ needed: boolean }>('/api/setup', { anonymous: true });
		if (s.needed) goto('/setup', { replaceState: true });
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		busy = true;
		try {
			if (ticket) {
				await post('/api/auth/login/totp', { ticket, code }, { anonymous: true });
			} else {
				const r = await post<{ status: string; ticket?: string }>(
					'/api/auth/login',
					{ username, password },
					{ anonymous: true }
				);
				if (r.status === 'totp') {
					ticket = r.ticket ?? null;
					busy = false;
					return;
				}
			}
			await goto(next, { replaceState: true, invalidateAll: true });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка входа';
			if (err instanceof ApiError && err.code === 'ticket_expired') {
				ticket = null;
				code = '';
			}
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>Вход · groupbase</title></svelte:head>

<h1>{ticket ? 'Код подтверждения' : 'Вход'}</h1>
<p class="muted">
	{ticket
		? 'Введите 6 цифр из приложения-аутентификатора'
		: 'Аккаунт создаётся старостой или администратором'}
</p>

<form onsubmit={submit} novalidate>
	{#if ticket}
		<div>
			<label class="label" for="code">Код</label>
			<input
				id="code"
				class="input num"
				inputmode="numeric"
				autocomplete="one-time-code"
				maxlength="7"
				bind:value={code}
				required
			/>
		</div>
	{:else}
		<div>
			<label class="label" for="username">Имя пользователя</label>
			<input
				id="username"
				class="input"
				autocomplete="username"
				autocapitalize="none"
				spellcheck="false"
				bind:value={username}
				required
			/>
		</div>
		<div>
			<label class="label" for="password">Пароль</label>
			<input
				id="password"
				class="input"
				type="password"
				autocomplete="current-password"
				bind:value={password}
				required
			/>
		</div>
	{/if}
	{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	<Button variant="primary" type="submit" loading={busy}>{ticket ? 'Подтвердить' : 'Войти'}</Button>
	<p class="faint small">Забыли пароль? Попросите старосту выдать ссылку для сброса.</p>
</form>
