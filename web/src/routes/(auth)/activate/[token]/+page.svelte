<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { get, post } from '$lib/api';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';

	interface Info {
		username: string;
		displayName: string;
		purpose: 'activate' | 'reset';
	}

	let info = $state<Info | null>(null);
	let invalid = $state('');
	let password = $state('');
	let confirm = $state('');
	let error = $state('');
	let busy = $state(false);

	onMount(async () => {
		try {
			info = await get<Info>(`/api/auth/links/${page.params.token}`, { anonymous: true });
		} catch (e) {
			invalid = e instanceof Error ? e.message : 'Ссылка недействительна';
		}
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		if (password !== confirm) {
			error = 'Пароли не совпадают';
			return;
		}
		busy = true;
		error = '';
		try {
			const r = await post<{ status: string }>(
				`/api/auth/links/${page.params.token}`,
				{ password },
				{ anonymous: true }
			);
			await goto(r.status === 'login' ? '/login' : '/', {
				replaceState: true,
				invalidateAll: true
			});
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>Пароль · groupbase</title></svelte:head>

{#if invalid}
	<h1>Ссылка не работает</h1>
	<p class="muted">{invalid}</p>
{:else if info}
	<h1>{info.purpose === 'reset' ? 'Новый пароль' : `Привет, ${info.displayName}!`}</h1>
	<p class="muted">
		{info.purpose === 'reset'
			? 'Задайте новый пароль для'
			: 'Задайте пароль для входа. Ваше имя пользователя —'}
		<strong>{info.username}</strong>
	</p>
	<form onsubmit={submit}>
		<input type="text" autocomplete="username" value={info.username} hidden readonly />
		<PasswordFields bind:password bind:confirm />
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		<Button variant="primary" type="submit" loading={busy}>Сохранить и войти</Button>
	</form>
{:else}
	<p class="faint">Проверяем ссылку…</p>
{/if}
