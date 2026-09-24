<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { ApiError, get, post } from '$lib/api';
	import Button from '$lib/ui/Button.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import QrLogin from '$lib/auth/QrLogin.svelte';
	import { Fingerprint, X } from '@lucide/svelte';
	import { loginWithPasskey, passkeyError, passkeysSupported } from '$lib/auth/passkey';
	import { firstName } from '$lib/names';
	import {
		forgetAccount,
		knownAccounts,
		rememberDevice,
		setRememberDevice,
		type KnownAccount
	} from '$lib/accounts';
	import { toast } from '$lib/toasts.svelte';

	let username = $state('');
	let password = $state('');
	let code = $state('');
	let ticket = $state<string | null>(null);
	let useRecovery = $state(false);
	let forgot = $state(false);
	let mode = $state<'password' | 'qr'>('password');
	let accounts = $state<KnownAccount[]>([]);
	let chosen = $state<KnownAccount | null>(null);
	let other = $state(false);
	let remember = $state(true);
	let passwordEl: HTMLInputElement | undefined = $state();
	let error = $state('');
	let busy = $state(false);
	let canPasskey = $state(false);
	let keyBusy = $state(false);

	const next = $derived.by(() => {
		const n = page.url.searchParams.get('next');
		return n && /^\/(?![/\\])/.test(n) ? n : '/';
	});

	onMount(async () => {
		accounts = knownAccounts();
		remember = rememberDevice();
		canPasskey = passkeysSupported();
		const s = await get<{ needed: boolean }>('/api/setup', { anonymous: true });
		if (s.needed) goto('/setup', { replaceState: true });
	});

	function choose(a: KnownAccount) {
		chosen = a;
		username = a.username;
		error = '';
		queueMicrotask(() => passwordEl?.focus());
	}

	function forget(a: KnownAccount) {
		forgetAccount(a.userId);
		accounts = knownAccounts();
	}

	async function entered() {
		setRememberDevice(remember);
		await goto(next, { replaceState: true, invalidateAll: true });
	}

	async function passkey() {
		error = '';
		keyBusy = true;
		try {
			await loginWithPasskey();
			await entered();
		} catch (err) {
			error = passkeyError(err);
		} finally {
			keyBusy = false;
		}
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		busy = true;
		try {
			if (ticket) {
				const r = await post<{ recoveryLeft?: string }>(
					'/api/auth/login/totp',
					{ ticket, code },
					{ anonymous: true }
				);
				if (r.recoveryLeft !== undefined) {
					const left = Number(r.recoveryLeft);
					toast(
						left <= 3
							? `Осталось резервных кодов: ${left}. Получите новые в профиле`
							: `Вход по резервному коду. Осталось: ${left}`,
						left <= 3 ? 'error' : 'ok'
					);
				}
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
			await entered();
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

<h1>
	{ticket
		? 'Код подтверждения'
		: chosen
			? `Здравствуйте, ${firstName(chosen.displayName)}`
			: 'Вход'}
</h1>
<p class="muted">
	{ticket
		? useRecovery
			? 'Введите один из резервных кодов, которые вы сохранили при включении 2FA'
			: 'Введите 6 цифр из приложения-аутентификатора'
		: mode === 'qr'
			? 'Без логина и пароля — подтвердите вход с телефона'
			: 'Аккаунт создаётся старостой или администратором'}
</p>

{#if !ticket}
	<div class="modes" role="tablist" aria-label="Способ входа">
		<button role="tab" aria-selected={mode === 'password'} onclick={() => (mode = 'password')}
			>По паролю</button
		>
		<button role="tab" aria-selected={mode === 'qr'} onclick={() => (mode = 'qr')}
			>По QR-коду</button
		>
	</div>
{/if}

{#if mode === 'qr' && !ticket}
	<QrLogin onsuccess={entered} />
{:else}
	<form onsubmit={submit} novalidate>
		{#if ticket}
			<div>
				<label class="label" for="code">{useRecovery ? 'Резервный код' : 'Код'}</label>
				{#if useRecovery}
					<input
						id="code"
						class="input num"
						autocomplete="off"
						autocapitalize="none"
						spellcheck="false"
						placeholder="abcd-efgh"
						maxlength="12"
						bind:value={code}
						required
					/>
				{:else}
					<input
						id="code"
						class="input num"
						inputmode="numeric"
						autocomplete="one-time-code"
						maxlength="7"
						bind:value={code}
						required
					/>
				{/if}
				<button
					type="button"
					class="linkish"
					onclick={() => ((useRecovery = !useRecovery), (code = ''), (error = ''))}
				>
					{useRecovery ? 'Ввести код из приложения' : 'Нет доступа к телефону? Резервный код'}
				</button>
			</div>
		{:else if accounts.length && !chosen && !other}
			<p class="label">Кто входит?</p>
			<ul class="accounts">
				{#each accounts as a (a.userId)}
					<li>
						<button type="button" class="account" onclick={() => choose(a)}>
							<Avatar id={a.userId} name={a.displayName} size={44} />
							<span class="who">
								<strong>{a.displayName}</strong>
								<span class="faint small">{a.username}</span>
							</span>
						</button>
						<button
							type="button"
							class="forget"
							onclick={() => forget(a)}
							aria-label="Убрать {a.displayName} с этого устройства"
							title="Убрать с этого устройства"><X size={16} /></button
						>
					</li>
				{/each}
			</ul>
			<button type="button" class="linkish" onclick={() => (other = true)}>Другой аккаунт</button>
		{:else}
			{#if chosen}
				<div class="chosen">
					<Avatar id={chosen.userId} name={chosen.displayName} size={40} />
					<span class="who">
						<strong>{chosen.displayName}</strong>
						<span class="faint small">{chosen.username}</span>
					</span>
					<button
						type="button"
						class="linkish"
						onclick={() => ((chosen = null), (username = ''), (password = ''))}>Сменить</button
					>
				</div>
				<input type="text" autocomplete="username" value={username} hidden readonly />
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
			{/if}
			<div>
				<label class="label" for="password">Пароль</label>
				<input
					id="password"
					class="input"
					type="password"
					autocomplete="current-password"
					bind:this={passwordEl}
					bind:value={password}
					required
				/>
			</div>
			<label class="check">
				<input type="checkbox" bind:checked={remember} /> Это моё устройство — запомнить меня
			</label>
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
		{#if ticket || chosen || other || !accounts.length}
			<Button variant="primary" type="submit" loading={busy}
				>{ticket ? 'Подтвердить' : 'Войти'}</Button
			>
		{/if}
		{#if !ticket && canPasskey}
			<p class="or" aria-hidden="true"><span>или</span></p>
			<Button onclick={passkey} loading={keyBusy}
				><Fingerprint size={18} /> Войти по отпечатку или лицу</Button
			>
		{/if}
		{#if !ticket}
			<button
				type="button"
				class="linkish"
				onclick={() => (forgot = !forgot)}
				aria-expanded={forgot}>Забыли пароль или логин?</button
			>
			{#if forgot}
				<div class="tip forgot">
					<span
						>Пароль восстанавливает <strong>староста</strong> или администратор: попросите у них ссылку
						для сброса. Они покажут QR-код — отсканируйте его камерой телефона и задайте новый пароль.
						Логин тоже подскажет староста.</span
					>
				</div>
			{/if}
		{/if}
	</form>
{/if}

<style>
	.or {
		display: flex;
		align-items: center;
		gap: 12px;
		margin: 2px 0;
		color: var(--text-3);
		font-size: 13px;
	}
	.or::before,
	.or::after {
		content: '';
		flex: 1;
		height: 1px;
		background: var(--border);
	}
	.modes {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 4px;
		margin: 4px 0 8px;
		padding: 4px;
		border-radius: var(--r-full);
		background: var(--surface-2);
	}
	.modes button {
		height: 40px;
		border: 0;
		border-radius: var(--r-full);
		background: transparent;
		color: var(--text-2);
		font-weight: 580;
	}
	.modes button[aria-selected='true'] {
		background: var(--accent);
		color: var(--accent-text);
	}
	.accounts {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.accounts li {
		position: relative;
	}
	.account {
		display: flex;
		align-items: center;
		gap: 12px;
		width: 100%;
		padding: 10px 44px 10px 10px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		text-align: left;
	}
	.account:hover {
		border-color: var(--text);
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
		flex: 1;
	}
	.forget {
		position: absolute;
		top: 50%;
		right: 10px;
		transform: translateY(-50%);
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--text-3);
	}
	.forget:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.chosen {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.linkish {
		margin-top: 8px;
		padding: 0;
		border: 0;
		background: none;
		color: var(--text-2);
		font-size: 13.5px;
		text-decoration: underline;
		text-underline-offset: 3px;
	}
	.linkish:hover {
		color: var(--text);
	}
	.forgot {
		font-size: 14.5px;
	}
</style>
