<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { get, post } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import FioField from '$lib/auth/FioField.svelte';
	import { fioError, suggestUsername } from '$lib/names';
	import type { GroupRole } from '$lib/types';

	interface Info {
		groupName: string;
		university: string;
		role: GroupRole;
		valid: boolean;
	}

	let info = $state<Info | null>(null);
	let invalid = $state('');
	let loggedIn = $state(false);
	let displayName = $state('');
	let username = $state('');
	let usernameTouched = $state(false);
	$effect(() => {
		if (!usernameTouched) username = suggestUsername(displayName);
	});
	let password = $state('');
	let confirm = $state('');
	let error = $state('');
	let busy = $state(false);
	const token = $derived(page.params.token);

	onMount(async () => {
		try {
			info = await get<Info>(`/api/invites/${token}`, { anonymous: true });
			if (!info.valid) invalid = 'Приглашение истекло, отозвано или исчерпано';
		} catch (e) {
			invalid = e instanceof Error ? e.message : 'Приглашение недействительно';
		}
		try {
			await get('/api/me', { anonymous: true });
			loggedIn = true;
		} catch {
			loggedIn = false;
		}
	});

	async function register(e: SubmitEvent) {
		e.preventDefault();
		error = fioError(displayName) || (password !== confirm ? 'Пароли не совпадают' : '');
		if (error) return;
		busy = true;
		try {
			await post(
				`/api/invites/${token}/accept`,
				{ username, displayName, password },
				{ anonymous: true }
			);
			await goto('/', { replaceState: true, invalidateAll: true });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}

	async function join() {
		busy = true;
		error = '';
		try {
			await post(`/api/invites/${token}/join`);
			await goto('/', { replaceState: true, invalidateAll: true });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>Приглашение · groupbase</title></svelte:head>

{#if invalid}
	<h1>Приглашение не работает</h1>
	<p class="muted">{invalid}. Попросите у старосты новую ссылку.</p>
{:else if info}
	<p class="eyebrow">Приглашение</p>
	<h1>{info.groupName}</h1>
	<p class="muted">
		{info.university}{info.university ? ' · ' : ''}роль: {t.roles[info.role].toLowerCase()}
	</p>
	{#if loggedIn}
		<form onsubmit={(e) => (e.preventDefault(), join())}>
			<p>Вы уже вошли. Вступить в эту группу со своим аккаунтом?</p>
			{#if error}<p class="error-text" role="alert">{error}</p>{/if}
			<Button variant="primary" type="submit" loading={busy}>Вступить</Button>
		</form>
	{:else}
		<form onsubmit={register}>
			<FioField bind:value={displayName} />
			<div>
				<label class="label" for="uname">Имя пользователя для входа</label>
				<input
					id="uname"
					class="input"
					bind:value={username}
					autocomplete="username"
					autocapitalize="none"
					spellcheck="false"
					placeholder="ivanov.ivan"
					oninput={() => (usernameTouched = true)}
					required
				/>
				<p class="hint">Придумали по ФИО — можно поменять. Латиница, цифры, точка.</p>
				<p class="hint">Латиница, цифры, точка или дефис</p>
			</div>
			<PasswordFields bind:password bind:confirm />
			{#if error}<p class="error-text" role="alert">{error}</p>{/if}
			<Button variant="primary" type="submit" loading={busy}>Присоединиться</Button>
			<p class="faint small">Уже есть аккаунт? <a href="/login?next=/invite/{token}">Войдите</a></p>
		</form>
	{/if}
{:else}
	<p class="faint">Проверяем приглашение…</p>
{/if}
